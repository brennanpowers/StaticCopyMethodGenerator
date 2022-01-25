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
    public String generateStaticCopyMethod(final PsiClass psiClass) {
        List<PsiGetterSetter> getterSetters = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
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
            // Inform user that some fields could not be copied due to lack of getter/setter
            methodBuilder.append("\n");
            String warning = String.format(
                    "/* Unable to generate lines for Maps: %s */",
                    StringUtils.join(mapErrors, ", ")
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
                    appendArrayTypeCopier(gs, methodBuilder);

                } else if (gs.isCollectionType()) {
                    appendCollectionTypeCopier(gs, methodBuilder);

                } else if (gs.isMapType()) {
                    PsiFieldGenerationError error = new PsiFieldGenerationError(
                            gs.getField(),
                            PsiFieldGenerationErrorReason.MAP
                    );
                    errors.add(error);
                } else {
                    appendCopyField(gs, methodBuilder);
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
     *  Append the copy block for an array type
     */
    private void appendArrayTypeCopier(final PsiGetterSetter gs, final StringBuilder methodBuilder) {
        Optional<PsiMethod> staticCopyMethod = findStaticCopyMethodForType(gs.getField().getType().getDeepComponentType(), gs.getField().getProject());

        PsiType collectionParameterType = null;
        if (staticCopyMethod.isPresent()) {
            collectionParameterType = staticCopyMethod.get().getReturnType();
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
            appendCopyField(gs, methodBuilder);
        }
    }

    /**
     * Append the copy block for a collection type
     */
    private void appendCollectionTypeCopier(final PsiGetterSetter gs, final StringBuilder methodBuilder) {
        // Attempt to resolve the parameter of this collection
        PsiType parameterType = PsiUtil.extractIterableTypeParameter(gs.getField().getType(), false);
        Optional<PsiMethod> staticCopyMethod = Optional.empty();
        if (parameterType != null) {
            staticCopyMethod = findStaticCopyMethodForType(parameterType, gs.getField().getProject());
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
                String setterString = null;
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
                        appendCopyField(gs, methodBuilder);
                        break;
                }
                methodBuilder
                        .append(collectionCopier)
                        .append(setterString);
            } else {
                // If we couldn't determine the collection type then just append it naively
                appendCopyField(gs, methodBuilder);
            }
        } else {
            // If we couldn't find a static copy method for the collection parameter type then append it naively
            appendCopyField(gs, methodBuilder);
        }
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
    private void appendCopyField(final PsiGetterSetter gs, final StringBuilder methodBuilder) {
        String copyField = "";
        if (gs.isSetterFluent()) {
            copyField = computeCopyFieldForFluentSetter(gs, methodBuilder);
        } else {
            copyField = computeCopyFieldForStandardSetter(gs, methodBuilder);
        }
        methodBuilder.append(copyField);
    }

    /**
     * Compute the copy field line for fluent setters, e.g.
     * copy
     * .setFieldA(original.getFieldA())
     * .setFieldB(original.getFieldB())
     * <p>
     * It doesn't append the line to the StringBuilder but it needs it for context on whether it needs to terminate a statement
     */
    private String computeCopyFieldForFluentSetter(
            final PsiGetterSetter getterSetter,
            final StringBuilder methodBuilder
    ) {
        Optional<PsiMethod> staticCopyMethod = findStaticCopyMethodForType(
                getterSetter.getField().getType(),
                getterSetter.getField().getProject()
        );

        PsiMethod setter = getterSetter.getSetter();
        PsiMethod getter = getterSetter.getGetter();

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
        } else {
            String copyField = String.format(".%s(original.%s())\n", setter.getName(), getter.getName());
            copyFieldString.append(copyField);
        }
        return copyFieldString.toString();
    }

    /**
     * Compute the copy field line for standard setters, e.g.
     * copy.setFieldA(original.getFieldA());
     * copy.setFieldB(original.getFieldB());
     * <p>
     * It doesn't append the line to the StringBuilder but it needs it for context on whether to terminate the current statement
     */
    private String computeCopyFieldForStandardSetter(
            final PsiGetterSetter getterSetter,
            final StringBuilder methodBuilder
    ) {
        Optional<PsiMethod> staticCopyMethod = findStaticCopyMethodForType(
                getterSetter.getField().getType(),
                getterSetter.getField().getProject()
        );

        PsiMethod setter = getterSetter.getSetter();
        PsiMethod getter = getterSetter.getGetter();

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
        } else {
            String copyField = String.format("copy.%s(original.%s());", setter.getName(), getter.getName());
            copyFieldString.append(copyField);
        }
        return copyFieldString.toString();
    }

    /**
     * Find the static copy method for the given type in the scope of the given project, e.g.
     * <p>
     * Foo.copy(Foo original) { ...copy logic... }
     */
    private Optional<PsiMethod> findStaticCopyMethodForType(final PsiType type, final Project project) {
        String canonicalTypeText = type.getCanonicalText();
        PsiClass psiClass = JavaPsiFacade
                .getInstance(project)
                .findClass(canonicalTypeText, GlobalSearchScope.projectScope(project));
        if (psiClass != null) {
            List<PsiMethod> methods = Arrays.asList(psiClass.getMethods());
            List<PsiMethod> staticCopyMethods = methods
                    .stream()
                    .filter(method -> {
                        // First find the static copy methods, confirm they return the given type, and that they have parameters
                        return method.getName().equals("copy")
                                && method.getReturnType() != null
                                && method.getReturnType().equalsToText(canonicalTypeText)
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
                        return theParameter.getType().equalsToText(canonicalTypeText);
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
        SHALLOW
    }
}
