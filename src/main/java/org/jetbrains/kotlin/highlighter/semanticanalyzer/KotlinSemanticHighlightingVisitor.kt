/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package org.jetbrains.kotlin.highlighter.semanticanalyzer

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.KotlinType
import org.netbeans.modules.csl.api.ColoringAttributes
import org.netbeans.modules.csl.api.OffsetRange
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.descriptors.ClassKind

class KotlinSemanticHighlightingVisitor(val ktFile: KtFile,
                                        val result: AnalysisResult) : KtVisitorVoid() {

    private lateinit var bindingContext: BindingContext
    private val positions = hashMapOf<OffsetRange, Set<ColoringAttributes>>()

    fun computeHighlightingRanges(): Map<OffsetRange, Set<ColoringAttributes>> {
        positions.clear()
        bindingContext = result.bindingContext
        ktFile.acceptChildren(this)

        return positions
    }

    private fun highlight(styleAttributes: KotlinHighlightingAttributes, range: TextRange) {
        val offsetRange = OffsetRange(range.startOffset, range.endOffset)
        positions.put(offsetRange, hashSetOf(styleAttributes.styleKey))
    }

    private fun highlightSmartCast(range: TextRange, typeName: String) {
    }

    override fun visitElement(element: PsiElement) = element.acceptChildren(this)

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        val parentExpression = expression.parent
        if (parentExpression is KtThisExpression || parentExpression is KtSuperExpression) return

        val target = bindingContext[BindingContext.REFERENCE_TARGET, expression]?.let {
            if (it is ConstructorDescriptor) it.getContainingDeclaration() else it
        } ?: return

        val smartCast = bindingContext.get(BindingContext.SMARTCAST, expression)
        val typeName = smartCast?.let { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(smartCast) } ?: null

        when (target) {
            is TypeParameterDescriptor -> highlightTypeParameter(expression)
            is ClassDescriptor -> highlightClassDescriptor(expression, target)
            is PropertyDescriptor -> highlightProperty(expression, target, typeName)
            is VariableDescriptor -> highlightVariable(expression, target, typeName)
        }
        super.visitSimpleNameExpression(expression)
    }

    override fun visitTypeParameter(parameter: KtTypeParameter) {
        val identifier = parameter.nameIdentifier
        if (identifier != null) highlightTypeParameter(identifier)

        super.visitTypeParameter(parameter)
    }

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        val identifier = classOrObject.getNameIdentifier()
        val classDescriptor = bindingContext.get(BindingContext.CLASS, classOrObject)
        if (identifier != null && classDescriptor != null) {
            highlightClassDescriptor(identifier, classDescriptor)
        }

        super.visitClassOrObject(classOrObject)
    }

    override fun visitProperty(property: KtProperty) {
        val nameIdentifier = property.getNameIdentifier()
        if (nameIdentifier == null) return
        val propertyDescriptor = bindingContext[BindingContext.VARIABLE, property]
        if (propertyDescriptor is PropertyDescriptor) {
            highlightProperty(nameIdentifier, propertyDescriptor)
        } else {
            visitVariableDeclaration(property)
        }

        super.visitProperty(property)
    }

    override fun visitParameter(parameter: KtParameter) {
        val nameIdentifier = parameter.getNameIdentifier()
        if (nameIdentifier == null) return
        val propertyDescriptor = bindingContext[BindingContext.PRIMARY_CONSTRUCTOR_PARAMETER, parameter]
        if (propertyDescriptor is PropertyDescriptor) {
            highlightProperty(nameIdentifier, propertyDescriptor)
        } else {
            visitVariableDeclaration(parameter)
        }

        super.visitParameter(parameter)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        val nameIdentifier = function.getNameIdentifier()
        if (nameIdentifier != null) {
            highlight(KotlinHighlightingAttributes.FUNCTION_DECLARATION, nameIdentifier.getTextRange())
        }

        super.visitNamedFunction(function)
    }

    private fun visitVariableDeclaration(declaration: KtNamedDeclaration) {
        val declarationDescriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
        val nameIdentifier = declaration.getNameIdentifier()
        if (nameIdentifier != null && declarationDescriptor != null) {
            highlightVariable(nameIdentifier, declarationDescriptor)
        }
    }

    private fun highlightTypeParameter(element: PsiElement) =
            highlight(KotlinHighlightingAttributes.TYPE_PARAMETER, element.textRange)

    private fun highlightAnnotation(expression: PsiElement) {
        var range = expression.textRange
        val annotationEntry = PsiTreeUtil.getParentOfType(expression, KtAnnotationEntry::class.java,
                false, KtValueArgumentList::class.java)

        if (annotationEntry != null) {
            val atSymbol = annotationEntry.atSymbol
            if (atSymbol != null) range = TextRange(atSymbol.textRange.startOffset, expression.textRange.endOffset)
        }
        highlight(KotlinHighlightingAttributes.ANNOTATION, range)
    }

    private fun highlightClassDescriptor(element: PsiElement, target: ClassDescriptor) = when (target.kind) {
        ClassKind.INTERFACE -> highlight(KotlinHighlightingAttributes.INTERFACE, element.textRange)
        ClassKind.ANNOTATION_CLASS -> highlightAnnotation(element)
        ClassKind.ENUM_ENTRY -> highlight(KotlinHighlightingAttributes.STATIC_FINAL_FIELD, element.textRange)
        ClassKind.CLASS, ClassKind.OBJECT -> highlight(KotlinHighlightingAttributes.CLASS, element.textRange)
        else -> {}
    }

    private fun highlightProperty(element: PsiElement, descriptor: PropertyDescriptor, typeName: String? = null) {
        val range = element.textRange
        val mutable = descriptor.isVar
        val attributes = if (DescriptorUtils.isStaticDeclaration(descriptor)) {
            if (mutable) KotlinHighlightingAttributes.STATIC_FIELD else KotlinHighlightingAttributes.STATIC_FINAL_FIELD
        } else {
            if (mutable) KotlinHighlightingAttributes.FIELD else KotlinHighlightingAttributes.FINAL_FIELD
        }
        if (typeName != null) highlightSmartCast(element.textRange, typeName)
        highlight(attributes, range)
    }

    private fun highlightVariable(element: PsiElement, descriptor: DeclarationDescriptor, typeName: String? = null) {
        if (descriptor !is VariableDescriptor) return

        val attributes = when (descriptor) {
            is LocalVariableDescriptor -> {
                if (descriptor.isVar()) {
                    KotlinHighlightingAttributes.LOCAL_VARIABLE
                } else {
                    KotlinHighlightingAttributes.LOCAL_FINAL_VARIABLE
                }
            }

            is ValueParameterDescriptor -> KotlinHighlightingAttributes.PARAMETER_VARIABLE

            else -> KotlinHighlightingAttributes.LOCAL_VARIABLE
        }
        if (typeName != null) highlightSmartCast(element.textRange, typeName)
        highlight(attributes, element.textRange)
    }

}