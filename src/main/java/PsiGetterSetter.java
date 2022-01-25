import com.intellij.psi.*;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.psi.util.PsiUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Class for holding a field, its getter, and its setter, and its containing class
 */
public class PsiGetterSetter {
    private static final String QUALIFIED_COLLECTION_NAME = "java.util.Collection";
    private static final String QUALIFIED_LIST_NAME = "java.util.List";
    private static final String QUALIFIED_SET_NAME = "java.util.Set";
    private static final String QUALIFIED_QUEUE_NAME = "java.util.Queue";
    private static final String QUALIFIED_MAP_NAME = "java.util.Map";

    private final PsiField field;
    private final PsiMethod getter;
    private final PsiMethod setter;

    public PsiGetterSetter(final PsiField field) {
        this.field = field;
        this.getter = PropertyUtil.findGetterForField(field);
        this.setter = PropertyUtil.findSetterForField(field);
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

    public PsiClass getPsiClass() {
        return field.getContainingClass();
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

    /**
     * Determines if a setter is fluent.  A setter is fluent if it has a return type, that type is the same as its
     * class, and it returns `this`.
     */
    public boolean isSetterFluent() {
        // getName is nullable so protect against that
        String className = StringUtils.trimToEmpty(getPsiClass().getName());
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
        PsiType type = field.getType();
        return type.getCanonicalText().contains("[]");
    }

    /**
     * Determines if this field is a Collection type.  It is a collection type if its type or one of its super types
     * contains the string 'java.util.Collection'
     */
    public boolean isCollectionType() {
        return typeOrSuperTypeCanonicalTextContains(QUALIFIED_COLLECTION_NAME);
    }

    /**
     * Determines if this field is a Collection type.  It is a collection type if its type or one of its super types
     * contains the string 'java.util.Map'
     */
    public boolean isMapType() {
        return typeOrSuperTypeCanonicalTextContains(QUALIFIED_MAP_NAME);
    }

    private boolean typeOrSuperTypeCanonicalTextContains(final String canonicalText) {
        boolean isCollectionType = field.getType().getCanonicalText().contains(canonicalText);
        if (!isCollectionType) {
            isCollectionType = Arrays.stream(field.getType().getSuperTypes())
                    .anyMatch(type -> type.getCanonicalText().contains(canonicalText));
        }
        return isCollectionType;
    }

    /**
     * Determine what type of collection this field type is. Returns null if it is not a collection type.
     */
    @Nullable
    public CollectionType computeCollectionType() {
        if (!isCollectionType()) {
            return null;
        }
        String canonicalText = field.getType().getCanonicalText();
        if (canonicalText.contains(QUALIFIED_COLLECTION_NAME)) {
            return CollectionType.COLLECTION;
        } else if (canonicalText.contains(QUALIFIED_LIST_NAME)) {
            return CollectionType.LIST;
        } else if (canonicalText.contains(QUALIFIED_SET_NAME)) {
            return CollectionType.SET;
        } else if (canonicalText.contains(QUALIFIED_QUEUE_NAME)) {
            return CollectionType.QUEUE;
        }
        return null;
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
