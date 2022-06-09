/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.js.test.handlers

import org.jetbrains.kotlin.KtPsiSourceFileLinesMapping
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.TranslationMode
import org.jetbrains.kotlin.js.engine.*
import org.jetbrains.kotlin.js.parser.sourcemaps.*
import org.jetbrains.kotlin.js.test.utils.getAllFilesForRunner
import org.jetbrains.kotlin.js.test.utils.getBoxFunction
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.directives.JsEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.configuration.JsEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.utils.SteppingTestLoggedData
import org.jetbrains.kotlin.test.utils.checkSteppingTestResult
import org.jetbrains.kotlin.test.utils.formatAsSteppingTestExpectation
import java.io.File

/**
 * This class is an analogue of the [DebugRunner][org.jetbrains.kotlin.test.backend.handlers.DebugRunner] from JVM stepping tests.
 *
 * It runs a generated JavaScript file under a debugger, sets a breakpoint in the beginning of the `box` function
 * and performs the "step into" action until there is nothing more to step into. On each pause it records the source file name,
 * the source line and the function name of the current call frame, and compares this data with the expectations written in the test file.
 *
 * It uses sourcemaps for mapping locations in the generated JS file to the corresponding locations in the source Kotlin file.
 * Also, it assumes that the sourcemap contains absolute paths to source files. The relative paths are replaced with
 * absolute paths earlier by [JsSourceMapPathRewriter].
 *
 * Stepping tests only work with the IR backend. The legacy backend is not supported.
 *
 * For simplicity, only the [FULL][org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.TranslationMode.FULL] translation mode is
 * supported.
 *
 */
class JsDebugRunner(testServices: TestServices) : AbstractJsArtifactsCollector(testServices) {
    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        if (someAssertionWasFailed) return

        val globalDirectives = testServices.moduleStructure.allDirectives
        val esModules = JsEnvironmentConfigurationDirectives.ES_MODULES in globalDirectives

        if (esModules) return

        // This file generated in the FULL mode should be self-sufficient.
        val jsFilePath = getAllFilesForRunner(testServices, modulesToArtifact)[TranslationMode.FULL]?.single()
            ?: error("Only FULL translation mode is supported")

        val mainModule = JsEnvironmentConfigurator.getMainModule(testServices)

        val sourceMapFile = File("$jsFilePath.map")
        val sourceMap = when (val parseResult = SourceMapParser.parse(sourceMapFile)) {
            is SourceMapSuccess -> parseResult.value
            is SourceMapError -> error(parseResult.message)
        }

        runGeneratedCode(jsFilePath, sourceMap, mainModule)
    }

    private fun runGeneratedCode(
        jsFilePath: String,
        sourceMap: SourceMap,
        mainModule: TestModule,
    ) {
        sourceMap.debugVerbose(System.err, File(jsFilePath)) // TODO: Remove
        val (testFileWithBoxFunction, boxFunctionStartLine) = getBoxFunctionStartLocation(mainModule)
        val originalFileWithBoxFunction = testFileWithBoxFunction.originalFile

        System.err.println(originalFileWithBoxFunction.absoluteFile.normalize()) // TODO: Remove

        val boxFunctionLineInGeneratedFile =
            sourceMap.breakpointLineInGeneratedFile(originalFileWithBoxFunction, boxFunctionStartLine)

        if (boxFunctionLineInGeneratedFile < 0)
            error("Could not find the location of the 'box' function in the generated file")

        val debuggerFacade = NodeJsDebuggerFacade(jsFilePath)

        val loggedItems = mutableListOf<SteppingTestLoggedData>()
        debuggerFacade.run {
            with(debuggerFacade) {
                val boxFunctionBreakpoint = debugger.setBreakpointByUrl(boxFunctionLineInGeneratedFile, "file://$jsFilePath")
                debugger.resume()
                waitForResumeEvent()
                waitForPauseEvent {
                    it.reason == Debugger.PauseReason.OTHER && it.hitBreakpoints.contains(boxFunctionBreakpoint.breakpointId)
                }
                while (true) {
                    val topMostCallFrame = waitForPauseEvent().callFrames[0]
                    val scriptPath = scriptUrlByScriptId(topMostCallFrame.location.scriptId)
                    if (scriptPath != "file://$jsFilePath") break
                    addCallFrameInfoToLoggedItems(sourceMap, topMostCallFrame, loggedItems)
                    debugger.stepInto()
                    waitForResumeEvent()
                }
            }
        }
        checkSteppingTestResult(
            mainModule.frontendKind,
            mainModule.targetBackend ?: TargetBackend.JS_IR,
            testFileWithBoxFunction.originalFile,
            loggedItems
        )
    }

    private fun addCallFrameInfoToLoggedItems(
        sourceMap: SourceMap,
        topMostCallFrame: Debugger.CallFrame,
        loggedItems: MutableList<SteppingTestLoggedData>
    ) {
        sourceMap.getSourceLineForGeneratedLocation(topMostCallFrame.location)?.let { (sourceFile, sourceLine) ->
            val testFileName = testFileNameFromMappedLocation(sourceFile, sourceLine) ?: return
            val expectation =
                formatAsSteppingTestExpectation(testFileName, sourceLine + 1, topMostCallFrame.functionName, false)
            loggedItems.add(SteppingTestLoggedData(sourceLine + 1, false, expectation))
        }
    }

    /**
     * Returns the test file and the line number in that file where the body of the `box` function begins.
     */
    private fun getBoxFunctionStartLocation(mainModule: TestModule): Pair<TestFile, Int> {
        val boxFunction = getBoxFunction(testServices) ?: error("Missing 'box' function")
        val file = boxFunction.containingKtFile
        val mapping = KtPsiSourceFileLinesMapping(file)
        val firstStatementOffset = boxFunction.bodyBlockExpression?.firstStatement?.startOffset
            ?: boxFunction.bodyExpression?.startOffset
            ?: boxFunction.startOffset
        return mainModule.files.single { it.name == file.name } to mapping.getLineByOffset(firstStatementOffset)
    }

    /**
     * Maps the location in the source file to the location in the generated file.
     *
     * The Node.js debugger is not sourcemap-aware, so we need to set a breakpoint in the `box` function in the generated JS file.
     *
     * We don't know where the generated `box` function is located, so we use the source map to figure it out.
     *
     * This is basically what Intellij IDEA's built-in JavaScript debugger does when you set a breakpoint in a source file: it tries
     * to map the location of the breakpoint in the source file to a location in the generated file. Here we use a simplified
     * algorithm for that.
     */
    private fun SourceMap.breakpointLineInGeneratedFile(sourceFile: File, sourceLine: Int): Int {
        val sourceFileAbsolutePath = sourceFile.absoluteFile.normalize()
        var candidateSegment: Pair<Int, SourceMapSegment>? = null
        for ((generatedLineNumber, group) in groups.withIndex()) {
            for (segment in group.segments) {
                if (segment.sourceFileName?.let { File(it).absoluteFile.normalize() } != sourceFileAbsolutePath ||
                    segment.sourceLineNumber != sourceLine)
                    continue
                if (candidateSegment == null)
                    candidateSegment = generatedLineNumber to segment
                // Find the segment that points to the earliest column in the source file
                if (segment.sourceColumnNumber < candidateSegment.second.sourceColumnNumber)
                    candidateSegment = generatedLineNumber to segment
            }
        }
        return candidateSegment?.first ?: -1
    }

    private fun SourceMap.getSourceLineForGeneratedLocation(location: Debugger.Location): Pair<String, Int>? {

        fun SourceMapSegment.sourceFileAndLine() = sourceFileName!! to sourceLineNumber

        val group = groups.getOrNull(location.lineNumber)?.takeIf { it.segments.isNotEmpty() } ?: return null
        val columnNumber = location.columnNumber ?: return group.segments[0].sourceFileAndLine()
        val segment = if (columnNumber <= group.segments[0].generatedColumnNumber) {
            group.segments[0]
        } else {
            group.segments.find {
                columnNumber > it.generatedColumnNumber
            }
        }
        return segment?.sourceFileAndLine()
    }

    private fun testFileNameFromMappedLocation(originalFilePath: String, originalFileLineNumber: Int): String? {
        val originalFile = File(originalFilePath)
        return testServices.moduleStructure.modules.flatMap { it.files }.findLast {
            it.originalFile.absolutePath == originalFile.absolutePath && it.startLineNumberInOriginalFile <= originalFileLineNumber
        }?.name
    }
}

/**
 * A wrapper around [NodeJsInspectorClient] that handles all the ceremony and allows us to only care about executing common debugging
 * actions.
 *
 * @param jsFilePath the test file to execute and debug.
 */
private class NodeJsDebuggerFacade(jsFilePath: String) {

    private val inspector = NodeJsInspectorClient("js/js.translator/testData/runIrTestInNode.js", listOf(jsFilePath))

    private val scriptUrls = mutableMapOf<Runtime.ScriptId, String>()

    private var pausedEvent: Debugger.Event.Paused? = null

    init {
        inspector.onEvent { event ->
            when (event) {
                is Debugger.Event.ScriptParsed -> {
                    scriptUrls[event.scriptId] = event.url
                }
                is Debugger.Event.Paused -> {
                    pausedEvent = event
                }
                is Debugger.Event.Resumed -> {
                    pausedEvent = null
                }
                else -> {}
            }
        }
    }

    /**
     * By the time [body] is called, the execution is paused, no code is executed yet.
     */
    fun <T> run(body: suspend NodeJsInspectorClientContext.() -> T) = inspector.run {
        debugger.enable()
        debugger.setSkipAllPauses(false)
        runtime.runIfWaitingForDebugger()
        waitForPauseEvent { it.reason == Debugger.PauseReason.BREAK_ON_START }

        body()
    }

    fun scriptUrlByScriptId(scriptId: Runtime.ScriptId) = scriptUrls[scriptId] ?: error("unknown scriptId")

    suspend fun NodeJsInspectorClientContext.waitForPauseEvent(suchThat: (Debugger.Event.Paused) -> Boolean = { true }) =
        waitForValueToBecomeNonNull {
            pausedEvent?.takeIf(suchThat)
        }

    suspend fun NodeJsInspectorClientContext.waitForResumeEvent() = waitForConditionToBecomeTrue { pausedEvent == null }
}
