import com.intellij.codeInsight.generation.ClassMember;
import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PropertyUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

public class PsiTools {
    public static List<ClassMember> getClassMembers(final PsiClass psiClass) {
        List<ClassMember> members = new ArrayList<>();

        PsiField[] psiFields = psiClass.getFields();
        for (PsiField field : psiFields) {
            members.add(new PsiFieldMember(field));
        }

        return members;
    }
}
