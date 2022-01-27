package com.brennanpowers.plugins.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Class for generating a static copy method for a PsiClass
 */
public class CreateStaticCopyMethodStringGenerator {
    /**
     * Generate the static copy method for the given class
     */
    public String generateStaticCopyMethod(final PsiClass psiClass, final List<PsiField> fields) {
        List<PsiGetterSetter> getterSetters = new ArrayList<>();
        for (PsiField field : fields) {
            PsiGetterSetter gs = new PsiGetterSetter(field);
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
        List<PsiFieldGenerationError> errors = appendCopyFields(getterSetters, methodBuilder);

        // Make sure the last character added was a semicolon
        if (methodBuilder.lastIndexOf(";") != methodBuilder.length() - 1) {
            methodBuilder.append(";");
        }

        appendErrorsAndWarnings(errors, methodBuilder);

        methodBuilder.append("\n");
        methodBuilder.append("return copy;}"); // End method
        return methodBuilder.toString();
    }

    private void appendErrorsAndWarnings(
            final List<PsiFieldGenerationError> errors,
            final StringBuilder methodBuilder
    ) {
        List<String> fieldsWithoutGetterAndSetter = errors
                .stream()
                .filter(error -> error.getReason() == PsiFieldGenerationErrorReason.NO_GETTER_SETTER)
                .map(error -> error.getPsiField().getName())
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

        List<String> mapErrors = errors
                .stream()
                .filter(error -> error.getReason() == PsiFieldGenerationErrorReason.MAP)
                .map(error -> error.getPsiField().getName())
                .collect(Collectors.toList());

        if (!mapErrors.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Only able to generate shallow copies for Maps: %s */",
                    StringUtils.join(mapErrors, ", ")
            );
            methodBuilder.append(warning);
        }

        List<String> noCopyMethodErrors = errors
                .stream()
                .filter(error -> error.getReason() == PsiFieldGenerationErrorReason.NO_COPY_METHOD)
                .map(error -> error.getPsiField().getName())
                .collect(Collectors.toList());

        if (!noCopyMethodErrors.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Only able to generate shallow copies for the following fields due to lack of static copy method: %s */",
                    StringUtils.join(noCopyMethodErrors, ", ")
            );
            methodBuilder.append(warning);
        }

        List<String> indeterminateCollectionTypeErrors = errors
                .stream()
                .filter(error -> error.getReason() == PsiFieldGenerationErrorReason.INDETERMINATE_COLLECTION_TYPE)
                .map(error -> error.getPsiField().getName())
                .collect(Collectors.toList());

        if (!indeterminateCollectionTypeErrors.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Only able to generate shallow copies for these indeterminate collections: %s */",
                    StringUtils.join(indeterminateCollectionTypeErrors, ", ")
            );
            methodBuilder.append(warning);
        }

        List<String> indeterminateType = errors
                .stream()
                .filter(error -> error.getReason() == PsiFieldGenerationErrorReason.INDETERMINATE_TYPE)
                .map(error -> error.getPsiField().getName())
                .collect(Collectors.toList());

        if (!indeterminateType.isEmpty()) {
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Only able to generate shallow copies for these indeterminate collections: %s */",
                    StringUtils.join(indeterminateType, ", ")
            );
            methodBuilder.append(warning);
        }
    }

    /**
     * Append the copy field statements for all the given PsiGetterSetters to the given StringBuilder
     * Returns the fields where the generation could not occur due to lack of getters/setters
     */
    private List<PsiFieldGenerationError> appendCopyFields(
            final List<PsiGetterSetter> getterSetters,
            final StringBuilder methodBuilder
    ) {
        List<PsiFieldGenerationError> errors = new ArrayList<>();
        for (PsiGetterSetter gs : getterSetters) {
            if (gs.hasGetterAndSetter()) {
                if (gs.isArrayType()) {
                    Optional<PsiFieldGenerationError> errorMaybe = appendArrayTypeCopier(gs, methodBuilder);
                    errorMaybe.ifPresent(errors::add);
                } else if (gs.isCollectionType()) {
                    Optional<PsiFieldGenerationError> errorMaybe = appendCollectionTypeCopier(gs, methodBuilder);
                    errorMaybe.ifPresent(errors::add);
                } else if (gs.isMapType()) {
                    appendCopyField(gs, methodBuilder);
                    PsiFieldGenerationError error = new PsiFieldGenerationError(
                            gs.getField(),
                            PsiFieldGenerationErrorReason.MAP
                    );
                    errors.add(error);
                } else {
                    Optional<PsiFieldGenerationError> errorMaybe = appendCopyField(gs, methodBuilder);
                    errorMaybe.ifPresent(errors::add);
                }
            } else {
                PsiFieldGenerationError error = new PsiFieldGenerationError(
                        gs.getField(),
                        PsiFieldGenerationErrorReason.NO_GETTER_SETTER
                );
                errors.add(error);
            }
        }
        return errors;
    }

    /**
     * Append the copy block for an array type
     */
    private Optional<PsiFieldGenerationError> appendArrayTypeCopier(final PsiGetterSetter gs, final StringBuilder methodBuilder) {
        Optional<PsiMethod> staticCopyMethod = findStaticCopyMethodForType(
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
            boolean fieldIsProjectClass = findClassFromTypeInProject(
                    gs.getField().getType().getDeepComponentType(),
                    gs.getField().getProject())
                    .isPresent();

            if (fieldIsProjectClass) {
                PsiFieldGenerationError shallowCopyWarning = new PsiFieldGenerationError(gs.getField(), PsiFieldGenerationErrorReason.NO_COPY_METHOD);
                return Optional.of(shallowCopyWarning);
            } else {
                return Optional.empty();
            }
        }

        if (collectionParameterType != null) {
            String objectType = gs.getField().getType().getDeepComponentType().getPresentableText();
            String collectionName = String.format("%ss", StringUtils.uncapitalize(objectType));

            String collectionCopier = buildCollectionCopier(collectionParameterType, gs.getGetter(), staticCopyMethod.get(), collectionName);
            String setArray = String.format("copy.%s(%s.toArray(new %s[0]));", gs.getSetter().getName(), collectionName, objectType);

            methodBuilder
                    .append(collectionCopier)
                    .append(setArray);
        } else {
            // Just make a shallow copy and inform the user
            appendCopyField(gs, methodBuilder);
            PsiFieldGenerationError shallowCopyWarning = new PsiFieldGenerationError(gs.getField(), PsiFieldGenerationErrorReason.INDETERMINATE_TYPE);
            return Optional.of(shallowCopyWarning);
        }
        return Optional.empty();
    }

    /**
     * Append the copy block for a collection type
     */
    private Optional<PsiFieldGenerationError> appendCollectionTypeCopier(final PsiGetterSetter gs, final StringBuilder methodBuilder) {
        // Attempt to resolve the parameter of this collection
        PsiType parameterType = PsiUtil.extractIterableTypeParameter(gs.getField().getType(), false);
        Optional<PsiMethod> staticCopyMethod;
        if (parameterType != null) {
            staticCopyMethod = findStaticCopyMethodForType(parameterType, gs.getField().getProject());
        } else {
            appendCopyField(gs, methodBuilder);
            PsiFieldGenerationError shallowCopyWarning = new PsiFieldGenerationError(gs.getField(), PsiFieldGenerationErrorReason.INDETERMINATE_TYPE);
            return Optional.of(shallowCopyWarning);
        }

        if (staticCopyMethod.isPresent()) {
            String collectionName = String.format("%ss", StringUtils.uncapitalize(parameterType.getPresentableText()));

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
                        PsiFieldGenerationError shallowCopyWarning = new PsiFieldGenerationError(gs.getField(), PsiFieldGenerationErrorReason.INDETERMINATE_COLLECTION_TYPE);
                        return Optional.of(shallowCopyWarning);
                }
                methodBuilder
                        .append(collectionCopier)
                        .append(setterString);
            } else {
                // If we couldn't determine the collection type then just append it shallow
                appendCopyField(gs, methodBuilder);
                PsiFieldGenerationError shallowCopyWarning = new PsiFieldGenerationError(gs.getField(), PsiFieldGenerationErrorReason.INDETERMINATE_COLLECTION_TYPE);
                return Optional.of(shallowCopyWarning);
            }
        } else {
            // If we couldn't find a static copy method for the collection parameter type then append it shallow
            appendCopyField(gs, methodBuilder);
            // If the class is a project class then warn the user about a shallow copy
            boolean fieldIsProjectClass = findClassFromTypeInProject(parameterType, gs.getField().getProject()).isPresent();
            if (fieldIsProjectClass) {
                PsiFieldGenerationError shallowCopyWarning = new PsiFieldGenerationError(gs.getField(), PsiFieldGenerationErrorReason.NO_COPY_METHOD);
                return Optional.of(shallowCopyWarning);
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
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
    private Optional<PsiFieldGenerationError> appendCopyField(final PsiGetterSetter gs, final StringBuilder methodBuilder) {
        if (gs.isSetterFluent()) {
            return appendCopyFieldForFluentSetter(gs, methodBuilder);
        } else {
            return appendCopyFieldForStandardSetter(gs, methodBuilder);
        }
    }

    /**
     * Append the copy field line for fluent setters, e.g.
     * copy
     * .setFieldA(original.getFieldA())
     * .setFieldB(original.getFieldB())
     */
    private Optional<PsiFieldGenerationError> appendCopyFieldForFluentSetter(
            final PsiGetterSetter gs,
            final StringBuilder methodBuilder
    ) {
        Optional<PsiMethod> staticCopyMethod = findStaticCopyMethodForType(
                gs.getField().getType(),
                gs.getField().getProject()
        );

        PsiMethod setter = gs.getSetter();
        PsiMethod getter = gs.getGetter();

        StringBuilder copyFieldString = new StringBuilder();

        if (methodBuilder.lastIndexOf(";") == methodBuilder.length() - 1) {
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
        } else {
            String copyField = String.format(".%s(original.%s())\n", setter.getName(), getter.getName());
            copyFieldString.append(copyField);
            methodBuilder.append(copyFieldString);
            // If the class is a project class then warn the user about a shallow copy
            Optional<PsiClass> projectClassMaybe = findClassFromTypeInProject(gs.getField().getType(), gs.getField().getProject());
            // Only warn user if this class is in the project and is not an enum
            if (projectClassMaybe.isPresent() && !projectClassMaybe.get().isEnum()) {
                PsiFieldGenerationError shallowCopyWarning = new PsiFieldGenerationError(gs.getField(), PsiFieldGenerationErrorReason.NO_COPY_METHOD);
                return Optional.of(shallowCopyWarning);
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Compute the copy field line for standard setters, e.g.
     * copy.setFieldA(original.getFieldA());
     * copy.setFieldB(original.getFieldB());
     */
    private Optional<PsiFieldGenerationError> appendCopyFieldForStandardSetter(
            final PsiGetterSetter gs,
            final StringBuilder methodBuilder
    ) {
        Optional<PsiMethod> staticCopyMethod = findStaticCopyMethodForType(
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
        } else {
            String copyField = String.format("copy.%s(original.%s());", setter.getName(), getter.getName());
            copyFieldString.append(copyField);
            methodBuilder.append(copyFieldString);
            // If the class is a project class then warn the user about a shallow copy
            Optional<PsiClass> projectClassMaybe = findClassFromTypeInProject(gs.getField().getType(), gs.getField().getProject());
            if (projectClassMaybe.isPresent() && !projectClassMaybe.get().isEnum()) {
                PsiFieldGenerationError shallowCopyWarning = new PsiFieldGenerationError(gs.getField(), PsiFieldGenerationErrorReason.NO_COPY_METHOD);
                return Optional.of(shallowCopyWarning);
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Find the static copy method for the given type in the scope of the given project, e.g.
     * <p>
     * Foo.copy(Foo original) { ...copy logic... }
     */
    private Optional<PsiMethod> findStaticCopyMethodForType(final PsiType type, final Project project) {
        Optional<PsiClass> psiClassMaybe = findClassFromTypeInProject(type, project);
        if (psiClassMaybe.isPresent()) {
            PsiClass psiClass = psiClassMaybe.get();
            List<PsiMethod> methods = Arrays.asList(psiClass.getMethods());
            List<PsiMethod> staticCopyMethods = methods
                    .stream()
                    .filter(method -> {
                        // First find the static copy methods, confirm they return the given type, and that they have parameters
                        return method.getName().equals("copy")
                                && method.getReturnType() != null
                                && method.getReturnType().equalsToText(type.getCanonicalText())
                                && method.hasModifierProperty(PsiModifier.STATIC)
                                && method.hasParameters();
                    })
                    .filter(method -> {
                        // Now make sure it has exactly one parameter that is the same as the given type
                        PsiParameterList parameterList = method.getParameterList();
                        if (parameterList.isEmpty()) {
                            return false;
                        }
                        List<PsiParameter> allParameters = Arrays.asList(parameterList.getParameters());
                        if (allParameters.size() != 1) {
                            return false;
                        }
                        PsiParameter theParameter = allParameters.get(0);
                        return theParameter.getType().equalsToText(type.getCanonicalText());
                    })
                    .collect(Collectors.toList());
            // At this point there should really only be 0 or 1 methods in staticCopyMethods since we have filtered down to a single method signature
            if (staticCopyMethods.isEmpty()) {
                return Optional.empty();
            } else {
                return Optional.of(staticCopyMethods.get(0));
            }
        }
        return Optional.empty();
    }

    private static Optional<PsiClass> findClassFromTypeInProject(final PsiType type, final Project project) {
        String canonicalTypeText = type.getCanonicalText();
        PsiClass psiClass = JavaPsiFacade
                .getInstance(project)
                .findClass(canonicalTypeText, GlobalSearchScope.projectScope(project));
        return Optional.ofNullable(psiClass);
    }

    /**
     * Holds a field and the reason it could not be generated or a warning reason for its generation
     */
    private static class PsiFieldGenerationError {
        private PsiField psiField;
        private PsiFieldGenerationErrorReason reason;

        public PsiFieldGenerationError(
                final PsiField psiField,
                final PsiFieldGenerationErrorReason reason
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

        public PsiFieldGenerationErrorReason getReason() {
            return reason;
        }

        public void setReason(PsiFieldGenerationErrorReason reason) {
            this.reason = reason;
        }
    }

    private enum PsiFieldGenerationErrorReason {
        NO_GETTER_SETTER,
        MAP,
        NO_COPY_METHOD,
        INDETERMINATE_TYPE,
        INDETERMINATE_COLLECTION_TYPE,
        INFINITE_LOOP
    }
}
