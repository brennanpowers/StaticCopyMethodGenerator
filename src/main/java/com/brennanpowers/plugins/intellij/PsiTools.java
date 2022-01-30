package com.brennanpowers.plugins.intellij;

import com.intellij.codeInsight.generation.ClassMember;
import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.psi.*;
import com.intellij.psi.util.PropertyUtil;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class PsiTools {
    public static final String QUALIFIED_COLLECTION_NAME = "java.util.Collection";
    public static final String QUALIFIED_LIST_NAME = "java.util.List";
    public static final String QUALIFIED_SET_NAME = "java.util.Set";
    public static final String QUALIFIED_QUEUE_NAME = "java.util.Queue";
    public static final String QUALIFIED_MAP_NAME = "java.util.Map";

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

    /**
     * Determines if this field is an array type.  It is an array type if its type has '[]' in its canonical name
     */
    public static boolean isArrayType(final PsiField field) {
        PsiType type = field.getType();
        return type.getCanonicalText().contains("[]");
    }

    /**
     * Determines if this field is a Collection type.  It is a collection type if its type or one of its super types
     * contains the string 'java.util.Collection'
     */
    public static boolean isCollectionType(final PsiField field) {
        return typeOrSuperTypeCanonicalTextContains(field, QUALIFIED_COLLECTION_NAME);
    }

    /**
     * Determines if this field is a Collection type.  It is a collection type if its type or one of its super types
     * contains the string 'java.util.Map'
     */
    public static boolean isMapType(final PsiField field) {
        return typeOrSuperTypeCanonicalTextContains(field, QUALIFIED_MAP_NAME);
    }

    private static boolean typeOrSuperTypeCanonicalTextContains(final PsiField field, final String canonicalText) {
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
    public static CollectionType computeCollectionType(final PsiField field) {
        if (!isCollectionType(field)) {
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

}
