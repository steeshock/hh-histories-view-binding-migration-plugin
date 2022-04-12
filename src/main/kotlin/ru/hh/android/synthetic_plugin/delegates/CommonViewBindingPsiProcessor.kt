package ru.hh.android.synthetic_plugin.delegates

import org.jetbrains.kotlin.nj2k.replace
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.getOrCreateBody
import ru.hh.android.synthetic_plugin.extensions.*
import ru.hh.android.synthetic_plugin.model.ProjectInfo
import ru.hh.android.synthetic_plugin.utils.ClassParentsFinder
import ru.hh.android.synthetic_plugin.utils.Const

class CommonViewBindingPsiProcessor(
    projectInfo: ProjectInfo,
) : ViewBindingPsiProcessor(
    projectInfo,
) {
    override fun processActivity(ktClass: KtClass) {
        val body = ktClass.getOrCreateBody()
        importDirectives.forEach { bindingClassName ->
            val text = bindingClassName.toActivityPropertyFormat(hasMultipleBindingsInFile)
            val viewBindingDeclaration = projectInfo.psiFactory.createProperty(text)

            tryToAddAfterCompanionObject(body, viewBindingDeclaration)
        }
        replaceBindingInitializationInActivity(body)
    }

    override fun processFragment(ktClass: KtClass) {
        val body = ktClass.getOrCreateBody()
        importDirectives.forEach { bindingClassName ->
            val mutablePropertyText = bindingClassName.toMutablePropertyFormat(hasMultipleBindingsInFile)
            val immutablePropertyText = bindingClassName.toImmutablePropertyFormat(hasMultipleBindingsInFile)
            val mutableViewBinding = projectInfo.psiFactory.createProperty(mutablePropertyText)
            val immutableViewBinding = projectInfo.psiFactory.createProperty(immutablePropertyText)

            tryToAddAfterCompanionObject(body, mutableViewBinding, immutableViewBinding)

            addBindingInitializationForFragment(body, bindingClassName)
            addBindingDisposingForFragment(body, bindingClassName)
        }
        replaceBindingInitializationInFragment(body)
    }

    override fun processView(ktClass: KtClass) {
        val body = ktClass.getOrCreateBody()
        importDirectives.forEach { bindingClassName ->
            val text = bindingClassName.toViewPropertyFormat(hasMultipleBindingsInFile)
            val viewBindingDeclaration = projectInfo.psiFactory.createProperty(text)

            tryToAddAfterCompanionObject(body, viewBindingDeclaration)
        }
        tryToRemoveExistingViewInflaters(body)
    }

    override fun canHandle(parents: ClassParentsFinder, ktClass: KtClass) = false

    override fun processCustomCases(parents: ClassParentsFinder, ktClass: KtClass) = Unit

    private fun addBindingInitializationForFragment(
        body: KtClassBody,
        bindingClassName: String,
    ) {
        body.functions.find { it.name == "onCreateView" }?.let {
            val text = bindingClassName.toFragmentInitializationFormat(hasMultipleBindingsInFile)
            val viewBindingDeclaration = projectInfo.psiFactory.createExpression(text)
            viewBindingDeclaration.add(getNewLine())
            it.addAfter(viewBindingDeclaration, it.bodyBlockExpression?.lBrace)
        }
    }

    private fun addBindingDisposingForFragment(
        body: KtClassBody,
        bindingClassName: String,
    ) {
        var onDestroyViewFunc = body.functions.find { it.name == "onDestroyView" }

        // Create onDestroyView() fun if we don't have
        if (onDestroyViewFunc == null) {
            val newOnDestroyViewFunc = projectInfo.psiFactory.createFunction(Const.ON_DESTROY_FUNC_DECLARATION)
            body.addBefore(newOnDestroyViewFunc, body.rBrace)
        }
        onDestroyViewFunc = body.functions.find { it.name == "onDestroyView" }

        onDestroyViewFunc?.let {
            val text = bindingClassName.toFragmentDisposingFormat(hasMultipleBindingsInFile)
            val viewBindingDeclaration = projectInfo.psiFactory.createExpression(text)
            viewBindingDeclaration.add(getNewLine())
            it.addAfter(viewBindingDeclaration, it.bodyBlockExpression?.lBrace)
        }
    }

    private fun tryToRemoveExistingViewInflaters(
        body: KtClassBody,
    ) {
        body.declarations.filterIsInstance<KtClassInitializer>().forEach {
            val viewInflaters = it.body?.children?.find { element ->
                element.text.contains(Const.LAYOUT_INFLATER_PREFIX)
                        || element.text.contains(Const.VIEW_INFLATER_PREFIX)
            }

            // Remove whole init block if it has only one view inflater children
            if (viewInflaters != null && it.body?.children?.size == 1) {
                it.delete()
                return
            }
            viewInflaters?.delete()
        }
    }

    /**
     * Replace existing binding instantiation in setContentView() in Activities
     */
    private fun replaceBindingInitializationInActivity(body: KtClassBody) {
        body.functions.find { it.name == "onCreate" }?.let {
            it.bodyBlockExpression?.children?.find { element ->
                element.text.contains(Const.SET_CONTENT_VIEW_PREFIX)
            }?.let { setContentViewFun ->
                val layoutName = setContentViewFun.text.getLayoutNameFromContentView()
                val contentViewTextFun = getMainBindingForActivity(layoutName).toActivityContentViewFormat()
                val contentViewBindingExpression = projectInfo.psiFactory.createExpression(contentViewTextFun)
                setContentViewFun.replace(contentViewBindingExpression)
            }
        }
    }

    /**
     * Replace existing binding instantiation in onCreateView in Fragments
     */
    private fun replaceBindingInitializationInFragment(body: KtClassBody) {
        body.functions.find { it.name == "onCreateView" }?.let {
            it.bodyBlockExpression?.children?.filterIsInstance<KtReturnExpression>()?.let { returnExpression ->
                val layoutName = returnExpression.last().text.getLayoutNameFromReturnExpression()
                val returnBindingName = getMainBindingForFragment(layoutName).toFragmentOnCreateViewFormat()
                val returnBindingExpression = projectInfo.psiFactory.createExpression(returnBindingName)
                returnExpression.last().replace(returnBindingExpression)
            }
        }
    }
}