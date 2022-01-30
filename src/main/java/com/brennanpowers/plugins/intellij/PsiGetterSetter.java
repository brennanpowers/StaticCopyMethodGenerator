package com.brennanpowers.plugins.intellij;

import com.intellij.psi.*;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.psi.util.PsiUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Class for holding a field, its getter, and its setter, and its containing class
 */
public class PsiGetterSetter {

    private final PsiClass psiClass;
    private final PsiField field;
    private final PsiMethod getter;
    private final PsiMethod setter;

    public PsiGetterSetter(final PsiField field, final PsiClass psiClass) {
        this.psiClass = psiClass;
        this.field = field;
        this.getter = PropertyUtil.findGetterForField(field);
        this.setter = PropertyUtil.findSetterForField(field);
    }

    public PsiClass getPsiClass() {
        return psiClass;
    }

    public boolean hasGetter() {
        return getter != null;
    }

    public boolean hasSetter() {
        return setter != null;
    }

    public boolean hasGetterAndSetter() {
        return hasGetter() && hasSetter();
    }

    public PsiField getField() {
        return field;
    }

    public PsiMethod getGetter() {
        return getter;
    }

    public PsiMethod getSetter() {
        return setter;
    }

    public boolean isSuperField() {
        List<PsiType> superClassesOfClass = Arrays.asList(psiClass.getSuperTypes());
        PsiType typeOfField = field.getType();
        return superClassesOfClass.contains(typeOfField);
    }

    /**
     * Determines if a setter is fluent.  A setter is fluent if it has a return type, that type is the same as its
     * class, and it returns `this`.
     */
    public boolean isSetterFluent() {
        PsiClass containingClass = getField().getContainingClass();
        if (containingClass == null) {
            return false;
        }
        String className = StringUtils.trimToEmpty(containingClass.getQualifiedName());
        PsiReturnStatement[] returnStatements = PsiUtil.findReturnStatements(setter);
        boolean returnsThis = false;
        // Only assume it's fluent if it has a single return statement
        if (returnStatements.length == 1) {
            PsiExpression returnStatement = returnStatements[0].getReturnValue();
            if (returnStatement != null && returnStatement.getText() != null) {
                returnsThis = returnStatement.getText().equals("this");
                if (returnStatement.getType() != null) {
                    returnsThis &= returnStatement.getType().equalsToText(className);
                }
            }
        }
        return setter.getReturnType() != null && setter.getReturnType().equalsToText(className) && returnsThis;
    }

    /**
     * Determines if this field is an array type.  It is an array type if its type has '[]' in its canonical name
     */
    public boolean isArrayType() {
        return PsiTools.isArrayType(getField());
    }

    /**
     * Determines if this field is a Collection type.  It is a collection type if its type or one of its super types
     * contains the string 'java.util.Collection'
     */
    public boolean isCollectionType() {
        return PsiTools.isCollectionType(getField());
    }

    /**
     * Determines if this field is a Collection type.  It is a collection type if its type or one of its super types
     * contains the string 'java.util.Map'
     */
    public boolean isMapType() {
        return PsiTools.isMapType(getField());
    }

    /**
     * Determine what type of collection this field type is. Returns null if it is not a collection type.
     */
    @Nullable
    public CollectionType computeCollectionType() {
       return PsiTools.computeCollectionType(getField());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || getClass() != o.getClass()) return false;

        PsiGetterSetter that = (PsiGetterSetter) o;

        return new EqualsBuilder().append(field, that.field).append(getter, that.getter).append(setter, that.setter).isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(field).append(getter).append(setter).toHashCode();
    }
}
