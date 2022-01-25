import com.intellij.codeInsight.generation.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import org.apache.commons.lang.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

public class CreateStaticCopyMethodHandler extends GenerateMembersHandlerBase {
    public CreateStaticCopyMethodHandler() {
        super(null);
    }

    @Override
    protected ClassMember[] getAllOriginalMembers(final PsiClass psiClass) {
        List<ClassMember> classMembers = PsiTools.getClassMembers(psiClass);
        return classMembers.toArray(new ClassMember[0]);
    }

    @Override
    protected GenerationInfo[] generateMemberPrototypes(
            final PsiClass psiClass,
            final ClassMember originalMember
    ) throws IncorrectOperationException {
        CreateStaticCopyMethodStringGenerator generator = new CreateStaticCopyMethodStringGenerator();
        String copyMethodString = generator.generateStaticCopyMethod(psiClass);
        PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(psiClass.getProject());
        PsiMethod copyMethod = elementFactory.createMethodFromText(copyMethodString, psiClass);
        GenerationInfo[] result = new GenerationInfo[1];
        result[0] = new PsiGenerationInfo<>(copyMethod);
        return result;
    }
}
