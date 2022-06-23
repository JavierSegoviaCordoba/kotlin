/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.components

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KtAnalysisApiInternals
import org.jetbrains.kotlin.analysis.api.components.KtImplicitReceiver
import org.jetbrains.kotlin.analysis.api.components.KtScopeContext
import org.jetbrains.kotlin.analysis.api.components.KtScopeProvider
import org.jetbrains.kotlin.analysis.api.fir.KtFirAnalysisSession
import org.jetbrains.kotlin.analysis.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.analysis.api.fir.scopes.*
import org.jetbrains.kotlin.analysis.api.fir.symbols.KtFirAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.fir.symbols.KtFirEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.fir.symbols.KtFirFileSymbol
import org.jetbrains.kotlin.analysis.api.fir.symbols.KtFirNamedClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.fir.types.KtFirType
import org.jetbrains.kotlin.analysis.api.impl.base.scopes.KtCompositeScope
import org.jetbrains.kotlin.analysis.api.impl.base.scopes.KtCompositeTypeScope
import org.jetbrains.kotlin.analysis.api.impl.base.scopes.KtEmptyScope
import org.jetbrains.kotlin.analysis.api.scopes.KtScope
import org.jetbrains.kotlin.analysis.api.scopes.KtTypeScope
import org.jetbrains.kotlin.analysis.api.symbols.KtFileSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithMembers
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.LLFirResolveSession
import org.jetbrains.kotlin.analysis.utils.printer.getElementTextInContext
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.utils.delegateFields
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
import org.jetbrains.kotlin.fir.resolve.calls.FirSyntheticPropertiesScope
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.scopes.*
import org.jetbrains.kotlin.fir.scopes.impl.*
import org.jetbrains.kotlin.fir.symbols.ensureResolved
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import java.util.*

internal class KtFirScopeProvider(
    override val analysisSession: KtFirAnalysisSession,
    private val builder: KtSymbolByFirBuilder,
    private val project: Project,
    private val firResolveSession: LLFirResolveSession,
) : KtScopeProvider() {
    // KtFirScopeProvider is thread local, so it's okay to use the same session here
    private val scopeSession = analysisSession.getScopeSessionFor(analysisSession.useSiteSession)

    private val memberScopeCache = IdentityHashMap<KtSymbolWithMembers, KtScope>()
    private val declaredMemberScopeCache = IdentityHashMap<KtSymbolWithMembers, KtScope>()
    private val delegatedMemberScopeCache = IdentityHashMap<KtSymbolWithMembers, KtScope>()
    private val fileScopeCache = IdentityHashMap<KtFileSymbol, KtScope>()
    private val packageMemberScopeCache = IdentityHashMap<KtPackageSymbol, KtScope>()

    private inline fun <T> KtSymbolWithMembers.withFirForScope(crossinline body: (FirClass) -> T): T? {
        return when (this) {
            is KtFirNamedClassOrObjectSymbol -> {
                firSymbol.ensureResolved(FirResolvePhase.TYPES)
                body(firSymbol.fir)
            }
            is KtFirAnonymousObjectSymbol -> {
                firSymbol.ensureResolved(FirResolvePhase.TYPES)
                body(firSymbol.fir)
            }
            is KtFirEnumEntrySymbol -> {
                firSymbol.ensureResolved(FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE)
                val initializer = firSymbol.fir.initializer
                check(initializer is FirAnonymousObjectExpression) { "Unexpected enum entry initializer: ${initializer?.javaClass}" }
                body(initializer.anonymousObject)
            }
            else -> error { "Unknown KtSymbolWithDeclarations implementation ${this::class.qualifiedName}" }
        }
    }

    override fun getMemberScope(classSymbol: KtSymbolWithMembers): KtScope {
        return memberScopeCache.getOrPut(classSymbol) {
            val firScope = classSymbol.withFirForScope { fir ->
                val firSession = analysisSession.useSiteSession
                fir.unsubstitutedScope(
                    firSession,
                    scopeSession,
                    withForcedTypeCalculator = false
                )
            } ?: return@getOrPut getEmptyScope()

            KtFirDelegatingScope(firScope, builder, token)
        }
    }

    override fun getStaticMemberScope(symbol: KtSymbolWithMembers): KtScope {
        val firScope = symbol.withFirForScope { fir ->
            val firSession = analysisSession.useSiteSession
            fir.scopeProvider.getStaticScope(
                fir,
                firSession,
                scopeSession,
            )
        } ?: return getEmptyScope()
        return KtFirDelegatingScope(firScope, builder, token)
    }

    override fun getDeclaredMemberScope(classSymbol: KtSymbolWithMembers): KtScope {
        return declaredMemberScopeCache.getOrPut(classSymbol) {
            val firScope = classSymbol.withFirForScope {
                analysisSession.useSiteSession.declaredMemberScope(it)
            } ?: return@getOrPut getEmptyScope()

            KtFirDelegatingScope(firScope, builder, token)
        }
    }

    override fun getDelegatedMemberScope(classSymbol: KtSymbolWithMembers): KtScope {
        val declaredScope = (getDeclaredMemberScope(classSymbol) as? KtFirDelegatingScope)?.firScope
            ?: return delegatedMemberScopeCache.getOrPut(classSymbol) { getEmptyScope() }
        return delegatedMemberScopeCache.getOrPut(classSymbol) {
            val firScope = classSymbol.withFirForScope { fir ->
                val delegateFields = fir.delegateFields
                if (delegateFields.isNotEmpty()) {
                    val firSession = analysisSession.useSiteSession
                    FirDelegatedMemberScope(
                        firSession,
                        scopeSession,
                        fir,
                        declaredScope,
                        delegateFields
                    )
                } else null
            } ?: return@getOrPut getEmptyScope()

            KtFirDelegatedMemberScope(firScope, token, builder)
        }
    }

    override fun getFileScope(fileSymbol: KtFileSymbol): KtScope {
        return fileScopeCache.getOrPut(fileSymbol) {
            check(fileSymbol is KtFirFileSymbol) { "KtFirScopeProvider can only work with KtFirFileSymbol, but ${fileSymbol::class} was provided" }
            KtFirFileScope(fileSymbol, token, builder)
        }
    }

    override fun getEmptyScope(): KtScope {
        return KtEmptyScope(token)
    }

    override fun getPackageScope(packageSymbol: KtPackageSymbol): KtScope {
        return packageMemberScopeCache.getOrPut(packageSymbol) {
            KtFirPackageScope(
                packageSymbol.fqName,
                project,
                builder,
                token,
                GlobalSearchScope.allScope(project), // TODO
                analysisSession.targetPlatform,
            )
        }
    }


    override fun getCompositeScope(subScopes: List<KtScope>): KtScope {
        return KtCompositeScope(subScopes, token)
    }

    @OptIn(KtAnalysisApiInternals::class)
    override fun getTypeScope(type: KtType): KtTypeScope? {
        check(type is KtFirType) { "KtFirScopeProvider can only work with KtFirType, but ${type::class} was provided" }
        val firSession = firResolveSession.useSiteFirSession
        val firTypeScope = type.coneType.scope(
            firSession,
            scopeSession,
            FakeOverrideTypeCalculator.Forced
        ) ?: return null
        return KtCompositeTypeScope(
            listOf(
                convertToKtTypeScope(firTypeScope),
                convertToKtTypeScope(FirSyntheticPropertiesScope(firSession, firTypeScope))
            ),
            token
        )
    }

    override fun getScopeContextForPosition(
        originalFile: KtFile,
        positionInFakeFile: KtElement
    ): KtScopeContext {
        val towerDataContext =
            analysisSession.firResolveSession.getTowerContextProvider(originalFile).getClosestAvailableParentContext(positionInFakeFile)
                ?: error("Cannot find enclosing declaration for ${positionInFakeFile.getElementTextInContext()}")

        val implicitReceivers = towerDataContext.nonLocalTowerDataElements.mapNotNull { it.implicitReceiver }.distinct()
        val implicitKtReceivers = implicitReceivers.map { receiver ->
            KtImplicitReceiver(
                token,
                builder.typeBuilder.buildKtType(receiver.type),
                builder.buildSymbol(receiver.boundSymbol.fir),
            )
        }

        val implicitReceiverScopes = implicitReceivers.mapNotNull { it.implicitScope }
        val nonLocalScopes = towerDataContext.nonLocalTowerDataElements.mapNotNull { it.scope }.distinct()
        val firLocalScopes = towerDataContext.localScopes

        @OptIn(ExperimentalStdlibApi::class)
        val allKtScopes = buildList<KtScope> {
            implicitReceiverScopes.mapTo(this, ::convertToKtScope)
            nonLocalScopes.mapTo(this, ::convertToKtScope)
            firLocalScopes.mapTo(this, ::convertToKtScope)
        }

        return KtScopeContext(
            getCompositeScope(allKtScopes.asReversed()),
            implicitKtReceivers.asReversed(),
            token
        )
    }

    private fun convertToKtScope(firScope: FirScope): KtScope {
        return when (firScope) {
            is FirAbstractSimpleImportingScope -> KtFirNonStarImportingScope(firScope, builder, token)
            is FirAbstractStarImportingScope -> KtFirStarImportingScope(firScope, builder, project, token)
            is FirPackageMemberScope -> KtFirPackageScope(
                firScope.fqName,
                project,
                builder,
                token,
                GlobalSearchScope.allScope(project), // todo
                analysisSession.targetPlatform
            )
            is FirContainingNamesAwareScope -> KtFirDelegatingScope(firScope, builder, token)
            else -> TODO(firScope::class.toString())
        }
    }

    private fun convertToKtTypeScope(firScope: FirScope): KtTypeScope {
        return when (firScope) {
            is FirContainingNamesAwareScope -> KtFirDelegatingTypeScope(firScope, builder, token)
            else -> TODO(firScope::class.toString())
        }
    }
}