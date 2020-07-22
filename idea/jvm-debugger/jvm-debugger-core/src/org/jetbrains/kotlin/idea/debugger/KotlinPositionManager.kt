/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger

import com.intellij.debugger.MultiRequestPositionManager
import com.intellij.debugger.NoDataException
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.PositionManagerEx
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.jdi.VirtualMachineProxy
import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.VirtualMachineProxyImpl
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ThreeState
import com.intellij.xdebugger.frame.XStackFrame
import com.sun.jdi.*
import com.sun.jdi.request.ClassPrepareRequest
import org.jetbrains.kotlin.codegen.inline.KOTLIN_STRATA_NAME
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.idea.core.KotlinFileTypeFactory
import org.jetbrains.kotlin.idea.core.util.CodeInsightUtils
import org.jetbrains.kotlin.idea.core.util.getLineStartOffset
import org.jetbrains.kotlin.idea.debugger.breakpoints.getLambdasAtLineIfAny
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinDebuggerCaches
import org.jetbrains.kotlin.idea.debugger.stackFrame.KotlinStackFrame
import org.jetbrains.kotlin.idea.decompiler.classFile.KtClsFile
import org.jetbrains.kotlin.idea.inline.INLINE_USE_IDENTIFIER
import org.jetbrains.kotlin.idea.stubindex.KotlinSourceFilterScope
import org.jetbrains.kotlin.idea.util.ProjectRootsUtil
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import java.lang.reflect.Field
import java.nio.file.Paths
import java.util.ArrayList
import kotlin.Comparator

class KotlinPositionManager(private val myDebugProcess: DebugProcess) : MultiRequestPositionManager, PositionManagerEx() {
    private val stackFrameInterceptor: StackFrameInterceptor = myDebugProcess.project.getService()
    private val searchScope: GlobalSearchScope

    init {
        searchScope = determineSearchScope()
    }

    private val allKotlinFilesScope = object : DelegatingGlobalSearchScope(
        KotlinSourceFilterScope.projectAndLibrariesSources(GlobalSearchScope.allScope(myDebugProcess.project), myDebugProcess.project)
    ) {
        private val projectIndex = ProjectRootManager.getInstance(myDebugProcess.project).fileIndex
        private val scopeComparator = Comparator.comparing(projectIndex::isInSourceContent).thenComparing(projectIndex::isInLibrarySource)
            .thenComparing { file1, file2 -> super.compare(file1, file2) }

        override fun compare(file1: VirtualFile, file2: VirtualFile): Int = scopeComparator.compare(file1, file2)
    }

    private val sourceSearchScopes: List<GlobalSearchScope> = listOf(
        myDebugProcess.searchScope,
        allKotlinFilesScope
    )

    override fun getAcceptedFileTypes(): Set<FileType> = KotlinFileTypeFactory.KOTLIN_FILE_TYPES_SET

    override fun evaluateCondition(
        context: EvaluationContext,
        frame: StackFrameProxyImpl,
        location: Location,
        expression: String
    ): ThreeState? {
        return ThreeState.UNSURE
    }

    override fun createStackFrame(frame: StackFrameProxyImpl, debugProcess: DebugProcessImpl, location: Location): XStackFrame? =
        if (location.isInKotlinSources()) {
            stackFrameInterceptor.createStackFrame(frame, debugProcess, location) ?: KotlinStackFrame(frame)
        } else
            null

    override fun getSourcePosition(location: Location?): SourcePosition? {
        if (location == null) throw NoDataException.INSTANCE

        val fileName = location.safeSourceName() ?: throw NoDataException.INSTANCE
        val lineNumber = location.safeLineNumber()
        if (lineNumber < 0) {
            throw NoDataException.INSTANCE
        }

        if (!DebuggerUtils.isKotlinSourceFile(fileName)) throw NoDataException.INSTANCE

        val psiFile = getAlternativeSource(location) ?: getPsiFileByLocation(location)

        if (psiFile == null) {
            val isKotlinStrataAvailable = location.declaringType().containsKotlinStrata()
            if (isKotlinStrataAvailable) {
                try {
                    val javaSourceFileName = location.sourceName("Java")
                    val javaClassName = JvmClassName.byInternalName(defaultInternalName(location))
                    val project = myDebugProcess.project

                    val defaultPsiFile = DebuggerUtils.findSourceFileForClass(
                        project, sourceSearchScopes, javaClassName, javaSourceFileName, location
                    )

                    if (defaultPsiFile != null) {
                        return SourcePosition.createFromLine(defaultPsiFile, 0)
                    }
                } catch (e: AbsentInformationException) {
                    // ignored
                }
            }

            throw NoDataException.INSTANCE
        }

        if (psiFile !is KtFile) throw NoDataException.INSTANCE

        // Zero-based line-number for Document.getLineStartOffset()
        val sourceLineNumber = location.safeLineNumber() - 1
        if (sourceLineNumber < 0) {
            throw NoDataException.INSTANCE
        }

        val lambdaOrFunIfInside = getLambdaOrFunIfInside(location, psiFile, sourceLineNumber)
        if (lambdaOrFunIfInside != null) {
            return SourcePosition.createFromElement(lambdaOrFunIfInside.bodyExpression!!)
        }

        val elementInDeclaration = getElementForDeclarationLine(location, psiFile, sourceLineNumber)
        if (elementInDeclaration != null) {
            return SourcePosition.createFromElement(elementInDeclaration)
        }

        val sameLineLocations = location.safeMethod()?.safeAllLineLocations()?.filter {
            it.safeLineNumber() == lineNumber && it.safeSourceName() == fileName
        }

        if (sameLineLocations != null) {
            // There're several locations for same source line. If same source position would be created for all of them,
            // breakpoints at this line will stop on every location.
            // Each location is probably some code in arguments between inlined invocations (otherwise same line locations would
            // have been merged into one), but it's impossible to correctly map locations to actual source expressions now.
            val locationIndex = sameLineLocations.indexOf(location)
            if (locationIndex > 0) {
                /*
                    `finally {}` block code is placed in the class file twice.
                    Unless the debugger metadata is available, we can't figure out if we are inside `finally {}`, so we have to check it using PSI.
                    This is conceptually wrong and won't work in some cases, but it's still better than nothing.
                */
                val elementAt = psiFile.getLineStartOffset(lineNumber)?.let { psiFile.findElementAt(it) }
                val isInsideDuplicatedFinally = elementAt != null && elementAt.getStrictParentOfType<KtFinallySection>() != null
                if (!isInsideDuplicatedFinally) {
                    return KotlinReentrantSourcePosition(SourcePosition.createFromLine(psiFile, sourceLineNumber))
                }
            }
        }

        return SourcePosition.createFromLine(psiFile, sourceLineNumber)
    }

    class KotlinReentrantSourcePosition(delegate: SourcePosition) : DelegateSourcePosition(delegate)

    private fun getAlternativeSource(location: Location): PsiFile? {
        val manager = PsiManager.getInstance(myDebugProcess.project)
        val qName = location.declaringType().name()
        val alternativeFileUrl = DebuggerUtilsEx.getAlternativeSourceUrl(qName, myDebugProcess.project) ?: return null
        val alternativePsiFile = VirtualFileManager.getInstance().findFileByUrl(alternativeFileUrl) ?: return null
        return manager.findFile(alternativePsiFile)
    }

    // Returns a property or a constructor if debugger stops at class declaration
    private fun getElementForDeclarationLine(location: Location, file: KtFile, lineNumber: Int): KtElement? {
        val lineStartOffset = file.getLineStartOffset(lineNumber) ?: return null
        val elementAt = file.findElementAt(lineStartOffset)
        val contextElement = getContextElement(elementAt)

        if (contextElement !is KtClass) return null

        val methodName = location.method().name()
        return when {
            JvmAbi.isGetterName(methodName) -> {
                val valueParameters = contextElement.primaryConstructor?.valueParameters ?: emptyList()
                valueParameters.find { it.hasValOrVar() && it.name != null && JvmAbi.getterName(it.name!!) == methodName }
            }
            methodName == "<init>" -> contextElement.primaryConstructor
            else -> null
        }
    }

    private fun getLambdaOrFunIfInside(location: Location, file: KtFile, lineNumber: Int): KtFunction? {
        val currentLocationFqName = location.declaringType().name() ?: return null

        val start = CodeInsightUtils.getStartLineOffset(file, lineNumber)
        val end = CodeInsightUtils.getEndLineOffset(file, lineNumber)
        if (start == null || end == null) return null

        val literalsOrFunctions = getLambdasAtLineIfAny(file, lineNumber)
        if (literalsOrFunctions.isEmpty()) return null

        val elementAt = file.findElementAt(start) ?: return null
        val typeMapper = KotlinDebuggerCaches.getOrCreateTypeMapper(elementAt)

        val currentLocationClassName =
            JvmClassName.byFqNameWithoutInnerClasses(FqName(currentLocationFqName)).internalName.replace('/', '.')

        for (literal in literalsOrFunctions) {
            if (InlineUtil.isInlinedArgument(literal, typeMapper.bindingContext, true)) {
                if (isInsideInlineArgument(literal, location, myDebugProcess as DebugProcessImpl, typeMapper.bindingContext)) {
                    return literal
                }
                continue
            }

            val internalClassNames = DebuggerClassNameProvider(
                myDebugProcess.project,
                searchScope,
                alwaysReturnLambdaParentClass = false
            ).getOuterClassNamesForElement(literal.firstChild, emptySet()).classNames

            if (internalClassNames.any { it == currentLocationClassName }) {
                return literal
            }
        }

        return null
    }

    private fun getPsiFileByLocation(location: Location): PsiFile? {
        val sourceName = location.safeSourceName() ?: return null

        val referenceInternalName = try {
            if (location.declaringType().containsKotlinStrata()) {
                //replace is required for windows
                location.sourcePath().replace('\\', '/')
            } else {
                defaultInternalName(location)
            }
        } catch (e: AbsentInformationException) {
            defaultInternalName(location)
        }

        val className = JvmClassName.byInternalName(referenceInternalName)

        val project = myDebugProcess.project

        return DebuggerUtils.findSourceFileForClass(project, sourceSearchScopes, className, sourceName, location)
    }

    private fun defaultInternalName(location: Location): String {
        //no stratum or source path => use default one
        val referenceFqName = location.declaringType().name()
        // JDI names are of form "package.Class$InnerClass"
        return referenceFqName.replace('.', '/')
    }

    override fun getAllClasses(sourcePosition: SourcePosition): List<ReferenceType> {
        val psiFile = sourcePosition.file
        if (psiFile is KtFile) {
            if (!ProjectRootsUtil.isInProjectOrLibSource(psiFile)) return emptyList()

            return hopelessAware {
                getReferenceTypesForPositionInKtFile(sourcePosition)
            } ?: emptyList()
        }

        if (psiFile is ClsFileImpl) {
            val decompiledPsiFile = psiFile.readAction { it.decompiledPsiFile }
            if (decompiledPsiFile is KtClsFile && runReadAction { sourcePosition.line } == -1) {
                val className = JvmFileClassUtil.getFileClassInternalName(decompiledPsiFile)
                return myDebugProcess.virtualMachineProxy.classesByName(className)
            }
        }

        throw NoDataException.INSTANCE
    }

    private fun getReferenceTypesForPositionInKtFile(sourcePosition: SourcePosition): List<ReferenceType> {
        val debuggerClassNameProvider = DebuggerClassNameProvider(
            myDebugProcess.project,
            searchScope
        )
        val lineNumber = runReadAction { sourcePosition.line }
        val classes = debuggerClassNameProvider.getClassesForPosition(sourcePosition)
        return classes.flatMap { className -> myDebugProcess.virtualMachineProxy.classesByName(className) }
            .flatMap { referenceType -> myDebugProcess.findTargetClasses(referenceType, lineNumber) }
    }

    fun originalClassNamesForPosition(position: SourcePosition): List<String> {
        val debuggerClassNameProvider = DebuggerClassNameProvider(
            myDebugProcess.project,
            searchScope,
            findInlineUseSites = false
        )
        return debuggerClassNameProvider.getOuterClassNamesForPosition(position)
    }

    override fun locationsOfLine(type: ReferenceType, position: SourcePosition): List<Location> {
        if (position.file !is KtFile) {
            throw NoDataException.INSTANCE
        }
        try {
            if (myDebugProcess.isDexDebug()) {
                val inlineLocations = runReadAction { getLocationsOfInlinedLine(type, position, myDebugProcess.searchScope) }
                if (inlineLocations.isNotEmpty()) {
                    return inlineLocations
                }
            }

            val line = position.line + 1

            val locations = type.locationsOfLine(KOTLIN_STRATA_NAME, null, line)
            if (locations == null || locations.isEmpty()) {
                throw NoDataException.INSTANCE
            }

            return locations.filter { it.sourceName(KOTLIN_STRATA_NAME) == position.file.name }
        } catch (e: AbsentInformationException) {
            throw NoDataException.INSTANCE
        }
    }

    @Deprecated("Since Idea 14.0.3 use createPrepareRequests fun")
    override fun createPrepareRequest(classPrepareRequestor: ClassPrepareRequestor, sourcePosition: SourcePosition): ClassPrepareRequest? {
        return createPrepareRequests(classPrepareRequestor, sourcePosition).firstOrNull()
    }

    override fun createPrepareRequests(requestor: ClassPrepareRequestor, position: SourcePosition): List<ClassPrepareRequest> {
        if (position.file !is KtFile) {
            throw NoDataException.INSTANCE
        }

        val classNames = DumbService.getInstance(myDebugProcess.project).runReadActionInSmartMode(Computable {
            DebuggerClassNameProvider(
                myDebugProcess.project,
                searchScope
            ).getOuterClassNamesForPosition(
                position
            )
        })
        return classNames.flatMap { name ->
            listOfNotNull(
                myDebugProcess.requestsManager.createClassPrepareRequest(requestor, name),
                myDebugProcess.requestsManager.createClassPrepareRequest(requestor, "$name$*")
            )
        }
    }

    private fun determineSearchScope(): GlobalSearchScope {
        if (KotlinDebuggerSettings.getInstance().debugDisableGradleInlineCalls)
            return myDebugProcess.searchScope

        val vmProxy = myDebugProcess.virtualMachineProxy
        val module = guessModuleByVM(vmProxy)
        return module?.getModuleWithDependenciesAndLibrariesScope(true) ?: myDebugProcess.searchScope
    }

    private fun guessModuleByVM(vmProxy: VirtualMachineProxy): Module? {
        if (vmProxy is VirtualMachineProxyImpl) {
            val vm = vmProxy.virtualMachine
            if (vm is PathSearchingVirtualMachine) {
                val baseDirectory = vm.baseDirectory()
                val classPaths = vm.classPath()
                val pathInfo: Field = vm.javaClass.getDeclaredField("pathInfo")
                pathInfo.setAccessible(true)
                val pathInfoValue = pathInfo.get(vm)
                val bootclasspaths = pathInfoValue.javaClass.getDeclaredField("bootclasspaths")
                bootclasspaths.setAccessible(true)
                val bootclasspathsValue = bootclasspaths.get(pathInfoValue) as List<String>
                val path = (classPaths + bootclasspathsValue).find { it.endsWith(INLINE_USE_IDENTIFIER) }
                val module = path?.let {
                    findModuleByPath(myDebugProcess.project, path.removeSuffix(INLINE_USE_IDENTIFIER))
                } ?: baseDirectory?.let {
                    findModuleByPath(myDebugProcess.project, baseDirectory)
                }
                return module
            }
        }
        return null
    }
}


fun findModuleByPath(project: Project, path: String): Module? {
    val vf = VfsUtil.findFile(Paths.get(path), true) ?: return null
    return ProjectFileIndex.getInstance(project).getModuleForFile(vf)
}

inline fun <U, V> U.readAction(crossinline f: (U) -> V): V {
    return runReadAction { f(this) }
}

private fun DebugProcess.findTargetClasses(outerClass: ReferenceType, lineAt: Int): List<ReferenceType> {
    val vmProxy = virtualMachineProxy

    try {
        if (!outerClass.isPrepared) {
            return emptyList()
        }
    } catch (e: ObjectCollectedException) {
        return emptyList()
    }

    val targetClasses = ArrayList<ReferenceType>(1)

    try {
        for (location in outerClass.safeAllLineLocations()) {
            val locationLine = location.lineNumber() - 1
            if (locationLine < 0) {
                // such locations are not correspond to real lines in code
                continue
            }

            if (lineAt == locationLine) {
                val method = location.method()
                if (method == null || com.intellij.debugger.engine.DebuggerUtils.isSynthetic(method) || method.isBridge) {
                    // skip synthetic methods
                    continue
                }

                targetClasses += outerClass
                break
            }
        }

        // The same line number may appear in different classes so we have to scan nested classes as well.
        // For example, in the next example line 3 appears in both Foo and Foo$Companion.

        /* class Foo {
            companion object {
                val a = Foo() /* line 3 */
            }
        } */

        val nestedTypes = vmProxy.nestedTypes(outerClass)
        for (nested in nestedTypes) {
            targetClasses += findTargetClasses(nested, lineAt)
        }
    } catch (_: AbsentInformationException) {
    }

    return targetClasses
}