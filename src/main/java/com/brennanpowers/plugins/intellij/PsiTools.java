package com.brennanpowers.plugins.intellij;

import com.intellij.codeInsight.generation.ClassMember;
import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.psi.*;
import com.intellij.psi.util.PropertyUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PsiTools {
    public static List<ClassMember> getClassMembers(final PsiClass psiClass) {
        List<PsiClass> classHierarchy = new ArrayList<>();
        classHierarchy.add(psiClass);
        PsiClass superClass = psiClass.getSuperClass();
        int maxLevels = 5;
        int levelCounter = 0;
        while (superClass != null && levelCounter < maxLevels) {
            classHierarchy.add(superClass);
            superClass = superClass.getSuperClass();
            levelCounter++;
        }
        List<ClassMember> members = new ArrayList<>();

        for (PsiClass currentClass : classHierarchy) {
            PsiField[] psiFields = currentClass.getFields();
            for (PsiField field : psiFields) {
                boolean addField = true;
                if (field.getModifierList() != null) {
                    if (field.getModifierList().hasModifierProperty(PsiModifier.STATIC)) {
                        addField = false;
                    }
                }
                if (addField) {
                    members.add(new PsiFieldMember(field));
                }
            }
        }


        return members;
    }

    public static boolean typesAreEqual(final PsiType left, final PsiType right) {
        if (left == null && right == null) {
            return true;
        } else if (left == null || right == null) {
            return false;
        }
        String canonicalLeft = left.getCanonicalText();
        String canonicalRight = right.getCanonicalText();
        return Objects.equals(canonicalLeft, canonicalRight);
    }
}
