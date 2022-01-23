import com.intellij.psi.*;
import com.intellij.psi.util.PropertyUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * Class for holding a field, its getter, and its setter
 */
public class PsiGetterSetter {
    private final PsiClass psiClass;
    private final PsiField field;
    private final PsiMethod getter;
    private final PsiMethod setter;

    public PsiGetterSetter(
            final PsiClass psiClass,
            final PsiField field) {
        this.psiClass = psiClass;
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

    public PsiField getField() {
        return field;
    }

    public PsiMethod getGetter() {
        return getter;
    }

    public PsiMethod getSetter() {
        return setter;
    }

    public boolean setterIsFluent() {
        String className = StringUtils.trimToEmpty(psiClass.getName());  // getName is nullable for some reason to protect against that
        return setter.getReturnType() != null && setter.getReturnType().equalsToText(className);
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
