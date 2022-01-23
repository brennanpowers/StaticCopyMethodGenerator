import com.intellij.codeInsight.generation.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
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
            final PsiClass aClass,
            final ClassMember originalMember
    ) throws IncorrectOperationException {
        PsiMethod copyMethod = this.generateStaticCopyMethod(aClass);
        GenerationInfo[] result = new GenerationInfo[1];
        result[0] = new PsiGenerationInfo<>(copyMethod);
        return result;
    }

    private PsiMethod generateStaticCopyMethod(final PsiClass psiClass) {
        List<PsiGetterSetter> getterSetters = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
            PsiGetterSetter gs = new PsiGetterSetter(psiClass, field);
            getterSetters.add(gs);
        }

        String className = StringUtils.trimToEmpty(psiClass.getName());  // getName is nullable for some reason to protect against that
        String methodSignature = String.format("public static %s copy(final %s original)", className, className);
        StringBuilder method = new StringBuilder(methodSignature);
        method.append("{"); // Begin method
        String copyInitializer = String.format("%s copy = new %s();", className, className);
        method.append(copyInitializer);

        List<String> fieldsWithoutGetterAndSetter = new ArrayList<>();
        for (PsiGetterSetter gs : getterSetters) {
            if (gs.hasGetterAndSetter()) {
                PsiMethod setter = gs.getSetter();
                PsiMethod getter = gs.getGetter();

                Optional<PsiMethod> staticCopyMethod = findStaticCopyMethodForType(gs.getField().getType(), gs.getField().getProject());

                if (gs.setterIsFluent()) {
                    if (method.lastIndexOf(";") == method.length() - 1) {
                        method.append("copy");
                    }
                    if (staticCopyMethod.isPresent()) {
                        PsiClass methodClass = staticCopyMethod.get().getContainingClass();
                        String methodClassName = "";
                        if (methodClass != null) {
                            methodClassName = methodClass.getName();
                        }
                        String copyFieldWithCopyMethod = String.format(".%s(%s.copy(original.%s()))", setter.getName(), methodClassName, getter.getName());
                        method.append(copyFieldWithCopyMethod);
                    } else {
                        String copyField = String.format(".%s(original.%s())\n", setter.getName(), getter.getName());
                        method.append(copyField);
                    }
                } else {
                    if (method.lastIndexOf(";") != method.length() - 1) {
                        method.append(";");
                    }
                    if (staticCopyMethod.isPresent()) {
                        PsiClass methodClass = staticCopyMethod.get().getContainingClass();
                        String methodClassName = "";
                        if (methodClass != null) {
                            methodClassName = methodClass.getName();
                        }
                        String copyFieldWithCopyMethod = String.format("copy.%s(%s.copy(original.%s()));", setter.getName(), methodClassName, getter.getName());
                        method.append(copyFieldWithCopyMethod);
                    } else {
                        String copyField = String.format("copy.%s(original.%s());", setter.getName(), getter.getName());
                        method.append(copyField);
                    }
                }
            } else {
                fieldsWithoutGetterAndSetter.add(gs.getField().getName());
            }
        }

        if (method.lastIndexOf(";") != method.length() - 1) {
            method.append(";");
        }

        if (!fieldsWithoutGetterAndSetter.isEmpty()) {
            // Inform user that some fields could not be copied due to lack of getter/setter
            method.append("\n");
            String warning = String.format("/* Could not generate lines for these fields due to lack of getter and setter: %s */", StringUtils.join(fieldsWithoutGetterAndSetter, ", "));
            method.append(warning);
        }

        method.append("\n");
        method.append("return copy;}"); // End method
        PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(psiClass.getProject());
        return elementFactory.createMethodFromText(method.toString(), psiClass);
    }

    public Optional<PsiMethod> findStaticCopyMethodForType(final PsiType type, final Project project) {
        String canonicalTypeText = type.getCanonicalText();
        PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(canonicalTypeText, GlobalSearchScope.projectScope(project));
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
}
