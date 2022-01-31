package com.brennanpowers.plugins.intellij;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Class for generating a static copy method for a PsiClass
 */
public class CreateStaticCopyMethodStringGenerator {
    private PsiType lastReturnTypeAdded = null;
    private final List<String> variableNames = new ArrayList<>();

    /**
     * Generate the static copy method for the given class
     */
    public String generateStaticCopyMethod(final PsiClass psiClass, final List<PsiField> fields) {
        List<PsiGetterSetter> getterSetters = new ArrayList<>();
        for (PsiField field : fields) {
            PsiGetterSetter gs = new PsiGetterSetter(field, psiClass);
            getterSetters.add(gs);
        }

        // getName is nullable so protect against that
        String className = StringUtils.trimToEmpty(psiClass.getName());
        String methodSignature = String.format("public static %s copy(final %s original)", className, className);
        StringBuilder methodBuilder = new StringBuilder(methodSignature);
        methodBuilder.append("{"); // Begin method
        // Add null check, returning null if original is null
        String nullCheck = ("if (original == null) { return null; }");
        methodBuilder.append(nullCheck);
        String copyInitializer = String.format("%s copy = new %s();", className, className);
        methodBuilder.append(copyInitializer);

        // Append all the copy field lines
        List<CopyFieldGenerationWarning> warnings = appendCopyFields(getterSetters, methodBuilder);

        // Make sure the last character added was a semicolon
        if (methodBuilder.lastIndexOf(";") != methodBuilder.length() - 1) {
            methodBuilder.append(";");
        }

        appendErrorsAndWarnings(warnings, methodBuilder);

        methodBuilder.append("\n");
        methodBuilder.append("return copy;}"); // End method
        return methodBuilder.toString();
    }

    /**
     * Append the copy field statements for all the given PsiGetterSetters to the given StringBuilder
     * Returns the fields where the generation could not occur due to lack of getters/setters
     */
    private List<CopyFieldGenerationWarning> appendCopyFields(
            final List<PsiGetterSetter> getterSetters,
            final StringBuilder methodBuilder
    ) {
        List<CopyFieldGenerationWarning> warnings = new ArrayList<>();
        for (PsiGetterSetter gs : getterSetters) {
            if (gs.hasGetterAndSetter()) {
                if (gs.isArrayType()) {
                    Optional<CopyFieldGenerationWarning> warningMaybe = appendArrayTypeCopier(gs, methodBuilder);
                    warningMaybe.ifPresent(warnings::add);
                } else if (gs.isCollectionType()) {
                    Optional<CopyFieldGenerationWarning> warningMaybe = appendCollectionTypeCopier(gs, methodBuilder);
                    warningMaybe.ifPresent(warnings::add);
                } else if (gs.isMapType()) {
                    appendCopyField(gs, methodBuilder);
                    CopyFieldGenerationWarning warning = new CopyFieldGenerationWarning(
                            gs.getField(),
                            CopyFieldGenerationWarningReason.MAP
                    );
                    warnings.add(warning);
                } else {
                    Optional<CopyFieldGenerationWarning> warningMaybe = appendCopyField(gs, methodBuilder);
                    warningMaybe.ifPresent(warnings::add);
                }
            } else {
                CopyFieldGenerationWarning warning = new CopyFieldGenerationWarning(
                        gs.getField(),
                        CopyFieldGenerationWarningReason.NO_GETTER_SETTER
                );
                warnings.add(warning);
            }
        }
        return warnings;
    }

    /**
     * Append the copy block for an array type
     */
    private Optional<CopyFieldGenerationWarning> appendArrayTypeCopier(
            final PsiGetterSetter gs,
            final StringBuilder methodBuilder
    ) {
        addSemicolonIfNeeded(methodBuilder);
        Optional<PsiMethod> staticCopyMethod = PsiTools.findStaticCopyMethodForType(
                gs.getField().getType().getDeepComponentType(),
                gs.getField().getProject()
        );

        PsiType collectionParameterType;
        if (staticCopyMethod.isPresent()) {
            collectionParameterType = staticCopyMethod.get().getReturnType();
        } else {
            // Just make a shallow copy and inform the user
            appendCopyField(gs, methodBuilder);

            // If the class is a project class then warn the user about a shallow copy
            boolean fieldIsProjectClass = PsiTools.findClassFromTypeInProject(
                            gs.getField().getType().getDeepComponentType(),
                            gs.getField().getProject())
                    .isPresent();

            if (fieldIsProjectClass) {
                CopyFieldGenerationWarning shallowCopyWarning = new CopyFieldGenerationWarning(
                        gs.getField(),
                        CopyFieldGenerationWarningReason.NO_COPY_METHOD
                );
                return Optional.of(shallowCopyWarning);
            } else {
                return Optional.empty();
            }
        }

        if (collectionParameterType != null) {
            String objectType = gs.getField().getType().getDeepComponentType().getPresentableText();
            String collectionName = String.format("%ss", StringUtils.uncapitalize(objectType));

            // Find non-clashing variable name
            collectionName = computeUniqueVariableName(collectionName);
            variableNames.add(collectionName);
            String nullCheck = String.format("if (original.%s() != null) {", gs.getGetter().getName());
            methodBuilder.append(nullCheck);

            String collectionCopier = buildCollectionCopier(
                    collectionParameterType,
                    gs.getGetter(),
                    staticCopyMethod.get(),
                    collectionName
            );

            String setArray = String.format(
                    "copy.%s(%s.toArray(new %s[0]));",
                    gs.getSetter().getName(),
                    collectionName,
                    objectType
            );

            lastReturnTypeAdded = gs.getSetter().getReturnType();

            addSemicolonIfNeeded(methodBuilder);

            String elseSetNull = String.format("} else { copy.%s(null); }", gs.getSetter().getName());

            methodBuilder
                    .append(collectionCopier)
                    .append(setArray)
                    .append(elseSetNull);

            Optional<CopyFieldGenerationWarning> infiniteLoopWarningsMaybe = detectPossibleInfiniteLoop(gs);
            return infiniteLoopWarningsMaybe;
        } else {
            // Just make a shallow copy and inform the user
            appendCopyField(gs, methodBuilder);
            CopyFieldGenerationWarning shallowCopyWarning = new CopyFieldGenerationWarning(
                    gs.getField(),
                    CopyFieldGenerationWarningReason.INDETERMINATE_TYPE
            );
            return Optional.of(shallowCopyWarning);
        }
    }

    /**
     * Append the copy block for a collection type
     */
    private Optional<CopyFieldGenerationWarning> appendCollectionTypeCopier(
            final PsiGetterSetter gs,
            final StringBuilder methodBuilder
    ) {
        addSemicolonIfNeeded(methodBuilder);
        // Attempt to resolve the parameter of this collection
        PsiType parameterType = PsiUtil.extractIterableTypeParameter(gs.getField().getType(), false);
        Optional<PsiMethod> staticCopyMethod;
        if (parameterType != null) {
            staticCopyMethod = PsiTools.findStaticCopyMethodForType(parameterType, gs.getField().getProject());
        } else {
            appendCopyField(gs, methodBuilder);
            CopyFieldGenerationWarning shallowCopyWarning = new CopyFieldGenerationWarning(
                    gs.getField(),
                    CopyFieldGenerationWarningReason.INDETERMINATE_TYPE
            );
            return Optional.of(shallowCopyWarning);
        }

        if (staticCopyMethod.isPresent()) {
            String collectionName = String.format("%ss", StringUtils.uncapitalize(parameterType.getPresentableText()));
            collectionName = computeUniqueVariableName(collectionName);
            variableNames.add(collectionName);

            String nullCheck = String.format("if (original.%s() != null) {", gs.getGetter().getName());
            methodBuilder.append(nullCheck);

            // Build the for loop
            String collectionCopier = buildCollectionCopier(
                    parameterType,
                    gs.getGetter(),
                    staticCopyMethod.get(),
                    collectionName
            );

            // Now determine how to set the copy's collection from the List that was generated in the for loop
            // If the user doesn't want an underlying arraylist or hashset, tough luck
            CollectionType collectionType = gs.computeCollectionType();
            if (collectionType != null) {
                String setterString;
                switch (collectionType) {
                    case COLLECTION:
                    case LIST:
                        setterString = String.format("copy.%s(%s);", gs.getSetter().getName(), collectionName);
                        break;
                    case SET:
                        setterString = String.format(
                                "copy.%s(new HashSet<>(%s));", gs.getSetter().getName(), collectionName
                        );
                        break;
                    case QUEUE:
                    default:
                        // We don't handle other collection types
                        appendCopyField(gs, methodBuilder);
                        CopyFieldGenerationWarning shallowCopyWarning = new CopyFieldGenerationWarning(
                                gs.getField(),
                                CopyFieldGenerationWarningReason.INDETERMINATE_COLLECTION_TYPE);
                        return Optional.of(shallowCopyWarning);
                }
                lastReturnTypeAdded = gs.getSetter().getReturnType();

                addSemicolonIfNeeded(methodBuilder);

                String elseSetNull = String.format("} else { copy.%s(null); }", gs.getSetter().getName());
                methodBuilder
                        .append(collectionCopier)
                        .append(setterString)
                        .append(elseSetNull);

                Optional<CopyFieldGenerationWarning> infiniteLoopWarningsMaybe = detectPossibleInfiniteLoop(gs);
                return infiniteLoopWarningsMaybe;
            } else {
                // If we couldn't determine the collection type then just append it shallow
                appendCopyField(gs, methodBuilder);
                CopyFieldGenerationWarning shallowCopyWarning = new CopyFieldGenerationWarning(
                        gs.getField(),
                        CopyFieldGenerationWarningReason.INDETERMINATE_COLLECTION_TYPE
                );
                return Optional.of(shallowCopyWarning);
            }
        } else {
            // If we couldn't find a static copy method for the collection parameter type then append it shallow
            appendCopyField(gs, methodBuilder);
            // If the class is a project class then warn the user about a shallow copy
            boolean fieldIsProjectClass = PsiTools.findClassFromTypeInProject(
                    parameterType,
                    gs.getField().getProject()
            ).isPresent();
            if (fieldIsProjectClass) {
                CopyFieldGenerationWarning shallowCopyWarning = new CopyFieldGenerationWarning(
                        gs.getField(),
                        CopyFieldGenerationWarningReason.NO_COPY_METHOD
                );
                return Optional.of(shallowCopyWarning);
            } else {
                return Optional.empty();
            }
        }
    }

    private boolean addSemicolonIfNeeded(StringBuilder methodBuilder) {
        int lastIndex = methodBuilder.length() - 1;
        if (methodBuilder.lastIndexOf(";") != lastIndex) {
            if (methodBuilder.lastIndexOf("\n") == lastIndex) {
                methodBuilder.replace(lastIndex, lastIndex, ";");
            } else {
                methodBuilder.append(";");
            }
            return true;
        }
        return false;
    }

    private String computeUniqueVariableName(final String variableName) {
        // Find non-clashing variable name
        String uniqueVariableName = variableName;
        if (variableNames.contains(uniqueVariableName)) {
            int i = 1;
            while (variableNames.contains(uniqueVariableName)) {
                // Just add 1, 2, 3, etc to the variable name until it does not have a match
                uniqueVariableName = String.format("%s%d", variableName, i);
                i++;
            }
        }
        return uniqueVariableName;
    }

    /**
     * Construct the String for copying collections
     */
    private String buildCollectionCopier(
            final PsiType collectionParameterType,
            final PsiMethod collectionGetter,
            final PsiMethod collectionParameterTypeStaticCopyMethod,
            final String collectionName
    ) {
        String objectType = collectionParameterType.getPresentableText();
        String copyMethodName = collectionParameterTypeStaticCopyMethod.getName();
        String objectName = StringUtils.uncapitalize(objectType);

        StringBuilder result = new StringBuilder();
        String listInitialization = String.format("List<%s> %s = new ArrayList<>();", objectType, collectionName);
        result.append(listInitialization);
        String forEach = String.format("for(%s t : original.%s())", objectType, collectionGetter.getName());
        result.append(forEach)
                .append("{")
                .append(String.format("%s %s = %s.%s(t);", objectType, objectName, objectType, copyMethodName))
                .append(String.format("%s.add(%s);", collectionName, objectName))
                .append("}");

        return result.toString();
    }

    /**
     * Append a normal copy field string to the given StringBuilder e.g.
     * <p>
     * copy.setFoo(original.getFoo());
     * <p>
     * Will attempt to use fluent setters if possible, e.g.
     * <p>
     * copy.setFoo(original.getFoo())
     * .setBar(original.getBar());
     */
    private Optional<CopyFieldGenerationWarning> appendCopyField(
            final PsiGetterSetter gs,
            final StringBuilder methodBuilder
    ) {
        Optional<CopyFieldGenerationWarning> warningMaybe;
        if (gs.isSetterFluent()) {
            warningMaybe = appendCopyFieldForFluentSetter(gs, methodBuilder);
        } else {
            warningMaybe = appendCopyFieldForStandardSetter(gs, methodBuilder);
        }
        lastReturnTypeAdded = gs.getSetter().getReturnType();
        return warningMaybe;
    }

    /**
     * Append the copy field line for fluent setters, e.g.
     * copy
     * .setFieldA(original.getFieldA())
     * .setFieldB(original.getFieldB())
     */
    private Optional<CopyFieldGenerationWarning> appendCopyFieldForFluentSetter(
            final PsiGetterSetter gs,
            final StringBuilder methodBuilder
    ) {
        Optional<PsiMethod> staticCopyMethod = PsiTools.findStaticCopyMethodForType(
                gs.getField().getType(),
                gs.getField().getProject()
        );

        PsiMethod setter = gs.getSetter();
        PsiMethod getter = gs.getGetter();

        StringBuilder copyFieldString = new StringBuilder();

        boolean lastIndexTerminates = methodBuilder.lastIndexOf(";") == methodBuilder.length() - 1
                || methodBuilder.lastIndexOf("}") == methodBuilder.length() - 1;

        if (lastIndexTerminates || !PsiTools.typesAreEqual(gs.getSetter().getReturnType(), lastReturnTypeAdded)) {
            copyFieldString.append("copy");
        }
        if (staticCopyMethod.isPresent()) {
            PsiClass methodClass = staticCopyMethod.get().getContainingClass();
            String methodClassName = "";
            if (methodClass != null) {
                methodClassName = methodClass.getName();
            }
            String copyFieldWithCopyMethod = String.format(
                    ".%s(%s.copy(original.%s()))",
                    setter.getName(),
                    methodClassName,
                    getter.getName()
            );
            copyFieldString.append(copyFieldWithCopyMethod);
            methodBuilder.append(copyFieldString);

            Optional<CopyFieldGenerationWarning> infiniteLoopWarningsMaybe = detectPossibleInfiniteLoop(gs);
            return infiniteLoopWarningsMaybe;
        } else {
            String copyField = String.format(".%s(original.%s())\n", setter.getName(), getter.getName());
            copyFieldString.append(copyField);
            methodBuilder.append(copyFieldString);
            // If the class is a project class then warn the user about a shallow copy
            Optional<PsiClass> projectClassMaybe = PsiTools.findClassFromTypeInProject(
                    gs.getField().getType(),
                    gs.getField().getProject()
            );
            // Only warn user if this class is in the project and is not an enum
            if (projectClassMaybe.isPresent() && !projectClassMaybe.get().isEnum()) {
                CopyFieldGenerationWarning shallowCopyWarning = new CopyFieldGenerationWarning(
                        gs.getField(),
                        CopyFieldGenerationWarningReason.NO_COPY_METHOD
                );
                return Optional.of(shallowCopyWarning);
            } else {
                return Optional.empty();
            }
        }
    }

    /**
     * Compute the copy field line for standard setters, e.g.
     * copy.setFieldA(original.getFieldA());
     * copy.setFieldB(original.getFieldB());
     */
    private Optional<CopyFieldGenerationWarning> appendCopyFieldForStandardSetter(
            final PsiGetterSetter gs,
            final StringBuilder methodBuilder
    ) {
        Optional<PsiMethod> staticCopyMethod = PsiTools.findStaticCopyMethodForType(
                gs.getField().getType(),
                gs.getField().getProject()
        );

        PsiMethod setter = gs.getSetter();
        PsiMethod getter = gs.getGetter();

        StringBuilder copyFieldString = new StringBuilder();
        if (methodBuilder.lastIndexOf(";") != methodBuilder.length() - 1) {
            copyFieldString.append(";");
        }
        if (staticCopyMethod.isPresent()) {
            PsiClass methodClass = staticCopyMethod.get().getContainingClass();
            String methodClassName = "";
            if (methodClass != null) {
                methodClassName = methodClass.getName();
            }
            String copyFieldWithCopyMethod =
                    String.format(
                            "copy.%s(%s.copy(original.%s()));", setter.getName(), methodClassName, getter.getName()
                    );
            copyFieldString.append(copyFieldWithCopyMethod);
            methodBuilder.append(copyFieldString);

            Optional<CopyFieldGenerationWarning> infiniteLoopWarningsMaybe = detectPossibleInfiniteLoop(gs);
            return infiniteLoopWarningsMaybe;
        } else {
            String copyField = String.format("copy.%s(original.%s());", setter.getName(), getter.getName());
            copyFieldString.append(copyField);
            methodBuilder.append(copyFieldString);
            // If the class is a project class then warn the user about a shallow copy
            Optional<PsiClass> projectClassMaybe = PsiTools.findClassFromTypeInProject(
                    gs.getField().getType(),
                    gs.getField().getProject()
            );
            if (projectClassMaybe.isPresent() && !projectClassMaybe.get().isEnum()) {
                CopyFieldGenerationWarning shallowCopyWarning = new CopyFieldGenerationWarning(
                        gs.getField(),
                        CopyFieldGenerationWarningReason.NO_COPY_METHOD
                );
                return Optional.of(shallowCopyWarning);
            } else {
                return Optional.empty();
            }
        }
    }

    private Optional<CopyFieldGenerationWarning> detectPossibleInfiniteLoop(
            final PsiGetterSetter gs
    ) {

        // Try to detect if this field's class has a static copy method amd if calls this field's class's static copy method
        // For example, suppose a class Foo has a field of type Bar, and Bar has a field of type Foo (a bi-directional relationship)
        // We want to warn the user if we detect that the statement copyFoo.setBar(Bar.copy(originalFoo.getBar()) is in Foo::copy
        // and copyBar.setFoo(Foo.copy(originalBar.getFoo())) is in Bar::copy

        // Using Generate Static Copy Method for Bar in these comments as an example
        PsiField field = gs.getField();
        // field = Bar.foo, now try to determine the type of Bar.foo
        PsiType fieldType = null;
        // We also want this too work for fields of type Foo[] and Collection<Foo>
        if (PsiTools.isArrayType(field)) {
            fieldType = field.getType().getDeepComponentType();
        } else if (PsiTools.isCollectionType(field)) {
            fieldType = PsiUtil.extractIterableTypeParameter(field.getType(), false);
        }
        if (fieldType == null) {
            fieldType = field.getType();
        }
        // fieldType = Foo
        Optional<PsiMethod> staticCopyMethodForField = PsiTools.findStaticCopyMethodForType(fieldType, field.getProject());
        if (staticCopyMethodForField.isEmpty()) {
            // if type Foo does not have a static copy method then no warning here
            return Optional.empty();
        }
        Optional<PsiClass> psiClassMaybe = PsiTools.findClassFromTypeInProject(
                fieldType,
                field.getProject()
        );
        if (gs.getPsiClass() == null || gs.getPsiClass().getQualifiedName() == null || psiClassMaybe.isEmpty()) {
            // If this class doesn't exist in the project then no warning here
            return Optional.empty();
        }
        // psiClass = Foo.class
        for (PsiField classField : psiClassMaybe.get().getAllFields()) {
            // Loop through the fields in Foo and try to find Bar
            PsiType classFieldType = classField.getType();
            // We also want this to work for types Bar[] and Collection<Bar>
            if (PsiTools.isArrayType(classField)) {
                classFieldType = classFieldType.getDeepComponentType();
            } else if (PsiTools.isCollectionType(classField)) {
                classFieldType = PsiUtil.extractIterableTypeParameter(classField.getType(), false);
            }
            if (classFieldType != null && classFieldType.equalsToText(gs.getPsiClass().getQualifiedName())) {
                // We have found a field of type Bar (or Bar[] or Collection<Bar>) in Foo
                if (staticCopyMethodForField.get().getBody() != null) {
                    // Now look in Foo's static copy method to see if it calls Bar::copy
                    for (PsiStatement statement : staticCopyMethodForField.get().getBody().getStatements()) {
                        // We found Bar::copy, create a warning for the user so they can deal with it
                        if (statement.getText().contains(String.format("%s.copy(", classFieldType.getPresentableText()))) {
                            CopyFieldGenerationWarning warning = new CopyFieldGenerationWarning(
                                    gs.getField(),
                                    CopyFieldGenerationWarningReason.INFINITE_LOOP
                            );
                            return Optional.of(warning);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private void appendErrorsAndWarnings(
            final List<CopyFieldGenerationWarning> warnings,
            final StringBuilder methodBuilder
    ) {
        List<String> fieldsWithoutGetterAndSetter = warnings
                .stream()
                .filter(w -> w.getReason() == CopyFieldGenerationWarningReason.NO_GETTER_SETTER)
                .map(w -> w.getPsiField().getName())
                .collect(Collectors.toList());
        if (!fieldsWithoutGetterAndSetter.isEmpty()) {
            // Inform user that some fields could not be copied due to lack of getter/setter
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Could not generate lines for these fields due to lack of getter and setter: %s */",
                    StringUtils.join(fieldsWithoutGetterAndSetter, ", ")
            );
            methodBuilder.append(warning);
        }

        List<String> mapErrors = warnings
                .stream()
                .filter(w -> w.getReason() == CopyFieldGenerationWarningReason.MAP)
                .map(w -> w.getPsiField().getName())
                .collect(Collectors.toList());

        if (!mapErrors.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Only able to generate shallow copies for Maps: %s */",
                    StringUtils.join(mapErrors, ", ")
            );
            methodBuilder.append(warning);
        }

        List<String> noCopyMethodErrors = warnings
                .stream()
                .filter(w -> w.getReason() == CopyFieldGenerationWarningReason.NO_COPY_METHOD)
                .map(w -> w.getPsiField().getName())
                .collect(Collectors.toList());

        if (!noCopyMethodErrors.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Only able to generate shallow copies for the following fields due to lack of static copy method: %s */",
                    StringUtils.join(noCopyMethodErrors, ", ")
            );
            methodBuilder.append(warning);
        }

        List<String> indeterminateCollectionTypeErrors = warnings
                .stream()
                .filter(w -> w.getReason() == CopyFieldGenerationWarningReason.INDETERMINATE_COLLECTION_TYPE)
                .map(w -> w.getPsiField().getName())
                .collect(Collectors.toList());

        if (!indeterminateCollectionTypeErrors.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Only able to generate shallow copies for these indeterminate collections: %s */",
                    StringUtils.join(indeterminateCollectionTypeErrors, ", ")
            );
            methodBuilder.append(warning);
        }

        List<String> indeterminateType = warnings
                .stream()
                .filter(w -> w.getReason() == CopyFieldGenerationWarningReason.INDETERMINATE_TYPE)
                .map(w -> w.getPsiField().getName())
                .collect(Collectors.toList());

        if (!indeterminateType.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Only able to generate shallow copies for these indeterminate types: %s */",
                    StringUtils.join(indeterminateType, ", ")
            );
            methodBuilder.append(warning);
        }

        List<String> infiniteLoop = warnings
                .stream()
                .filter(w -> w.getReason() == CopyFieldGenerationWarningReason.INFINITE_LOOP)
                .map(w -> w.getPsiField().getName())
                .collect(Collectors.toList());

        if (!infiniteLoop.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* POSSIBLE INFINITE LOOP DETECTED VIA COPY METHODS FOR THESE FIELDS: %s */",
                    StringUtils.join(infiniteLoop, ", ")
            );
            methodBuilder.append(warning);
        }
    }

    /**
     * Holds a field and the reason it could not be generated or a warning reason for its generation
     */
    private static class CopyFieldGenerationWarning {
        private PsiField psiField;
        private CopyFieldGenerationWarningReason reason;

        public CopyFieldGenerationWarning(
                final PsiField psiField,
                final CopyFieldGenerationWarningReason reason
        ) {
            this.psiField = psiField;
            this.reason = reason;
        }

        public PsiField getPsiField() {
            return psiField;
        }

        public void setPsiField(PsiField psiField) {
            this.psiField = psiField;
        }

        public CopyFieldGenerationWarningReason getReason() {
            return reason;
        }

        public void setReason(CopyFieldGenerationWarningReason reason) {
            this.reason = reason;
        }
    }

    private enum CopyFieldGenerationWarningReason {
        NO_GETTER_SETTER,
        MAP,
        NO_COPY_METHOD,
        INDETERMINATE_TYPE,
        INDETERMINATE_COLLECTION_TYPE,
        INFINITE_LOOP
    }
}
