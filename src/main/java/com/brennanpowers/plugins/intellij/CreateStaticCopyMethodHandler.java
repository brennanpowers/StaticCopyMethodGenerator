package com.brennanpowers.plugins.intellij;

import com.intellij.codeInsight.generation.*;
import com.intellij.psi.*;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class CreateStaticCopyMethodHandler extends GenerateMembersHandlerBase {
    public CreateStaticCopyMethodHandler() {
        super(null);
    }

    @Override
    protected ClassMember[] getAllOriginalMembers(final PsiClass psiClass) {
        List<ClassMember> classMembers = PsiTools.getClassMembers(psiClass);
        return classMembers.toArray(new ClassMember[0]);
    }

    @SuppressWarnings("rawtypes") // for un-paramaterized PsiElementClassMember
    @NotNull
    @Override
    protected List<? extends GenerationInfo> generateMemberPrototypes(
            final PsiClass psiClass,
            final ClassMember[] members
    ) throws IncorrectOperationException {
        List<PsiField> fields = new ArrayList<>();
        for (ClassMember member : members) {
            final PsiElementClassMember elementClassMember = (PsiElementClassMember) member;
            PsiField field = (PsiField) elementClassMember.getPsiElement();
            fields.add(field);
        }
        PsiMethod copyMethod = generateStaticCopyMethod(psiClass, fields);
        return Collections.singletonList(new PsiGenerationInfo<>(copyMethod));
    }

    @Override
    protected GenerationInfo[] generateMemberPrototypes(PsiClass aClass, ClassMember originalMember)
            throws IncorrectOperationException {
        throw new IncorrectOperationException();
    }

    @NotNull
    private PsiMethod generateStaticCopyMethod(final PsiClass psiClass, final List<PsiField> fields) {
        CreateStaticCopyMethodStringGenerator generator = new CreateStaticCopyMethodStringGenerator();
        String copyMethodString = generator.generateStaticCopyMethod(psiClass, fields);
        PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(psiClass.getProject());
        return elementFactory.createMethodFromText(copyMethodString, psiClass);
    }
}
