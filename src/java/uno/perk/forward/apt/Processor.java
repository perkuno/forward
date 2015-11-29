package uno.perk.forward.apt;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import uno.perk.forward.Forward;

/**
 * Generates Forwarder implementations for types annotated with {@link Forward}.
 */
public class Processor extends AbstractProcessor {

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Collections.singleton(Forward.class.getName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.RELEASE_7;
  }

  private static class ForwardMirror {
    final Iterable<TypeElement> forwardedTypes;
    final String forwarderPattern;

    ForwardMirror(Iterable<TypeElement> forwardedTypes, String forwarderPattern) {
      this.forwardedTypes = forwardedTypes;
      this.forwarderPattern = forwarderPattern;
    }
  }

  private static final Pattern CLASSNAME_MATCHER = Pattern.compile("(.*)");

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (Element element : roundEnv.getElementsAnnotatedWith(Forward.class)) {
      if (!(element instanceof TypeElement)) {
        error("@Forward was applied to an unexpected program element.", element);
      }
      TypeElement typeElement = (TypeElement) element;

      ForwardMirror forwardMirror = getForwardMirror(typeElement);
      Matcher classNameMatcher = CLASSNAME_MATCHER.matcher(typeElement.getSimpleName());
      String forwarderName = classNameMatcher.replaceFirst(forwardMirror.forwarderPattern);

      try {
        generateForwarder(typeElement, forwardMirror.forwardedTypes, forwarderName);
      } catch (IOException e) {
        error(String.format("Failed to write forwarder: %s.", e), element);
      }
    }
    return true;
  }

  private ForwardMirror getForwardMirror(TypeElement typeElement) {
    List<TypeElement> forwardedTypes = new LinkedList<>();
    String forwarderPattern = null;

    AnnotationMirror forwardAnnotation = getForwardAnnotation(typeElement);
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        getElementUtils().getElementValuesWithDefaults(forwardAnnotation).entrySet()) {

      String keyName = entry.getKey().getSimpleName().toString();
      Object value = entry.getValue().getValue();
      switch (keyName) {
        case "value":
          if (!(value instanceof List)) {
            error("Unexpected value for 'value'.", typeElement);
          }
          @SuppressWarnings("unchecked") // AnnotationValue guarantees this List parameterization.
          List<? extends AnnotationValue> types = (List<? extends AnnotationValue>) value;
          for (AnnotationValue type : types) {
            DeclaredType declaredType = (DeclaredType) type.getValue();
            TypeElement forwardedType = (TypeElement) declaredType.asElement();
            if (!forwardedType.getKind().isInterface()) {
              error("@Forward can only forward interface types.", typeElement);
            }
            forwardedTypes.add(forwardedType);
          }
          break;
        case "forwarderPattern":
          if (!(value instanceof String)) {
            error("Unexpected value for 'forwarderPattern'.", typeElement);
          }
          forwarderPattern = (String) value;
          break;
        default:
          error(
              String.format("Unexpected annotation member %s = %s.", keyName, value),
              typeElement);
          break;
      }
    }

    if (forwardedTypes.isEmpty() || forwarderPattern == null) {
      error("Failed to extract values from Forward annotation.", typeElement);
    }

    return new ForwardMirror(forwardedTypes, forwarderPattern);
  }

  private static final EnumSet<Modifier> PUBLIC_ABSTRACT =
      EnumSet.of(Modifier.PUBLIC, Modifier.ABSTRACT);

  private static class ForwardedTypeInfo {
    final TypeElement forwardedType;
    final TypeName typeName;
    final String delegateName;

    ForwardedTypeInfo(TypeElement forwardedType, TypeName typeName, String delegateName) {
      this.delegateName = delegateName;
      this.forwardedType = forwardedType;
      this.typeName = typeName;
    }
  }

  private void generateForwarder(
      TypeElement typeElement,
      Iterable<TypeElement> forwardedTypes,
      String forwarderName) throws IOException {

    TypeSpec.Builder forwarderBuilder = TypeSpec.classBuilder(forwarderName);
    MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder();

    List<ForwardedTypeInfo> forwardedTypeInfos = createForwardedTypeInfos(forwardedTypes);
    for (ForwardedTypeInfo forwardedTypeInfo : forwardedTypeInfos) {
      for (TypeParameterElement typeParam : forwardedTypeInfo.forwardedType.getTypeParameters()) {
        TypeVariable typeVariable = (TypeVariable) typeParam.asType();
        forwarderBuilder.addTypeVariable(TypeVariableName.get(typeVariable));
      }

      TypeName typeName = forwardedTypeInfo.typeName;
      String delegateName = forwardedTypeInfo.delegateName;
      forwarderBuilder
          .addSuperinterface(typeName)
          .addField(
              FieldSpec.builder(typeName, delegateName)
                  .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                  .build());

      constructorBuilder
          .addParameter(typeName, delegateName)
          .addStatement("this.$L = $L", delegateName, delegateName);
    }
    forwarderBuilder.addMethod(constructorBuilder.build());

    for (ForwardedTypeInfo forwardedTypeInfo : forwardedTypeInfos) {
      for (Element element : getElementUtils().getAllMembers(forwardedTypeInfo.forwardedType)) {
        if (element instanceof ExecutableElement) {
          ExecutableElement executableElement = (ExecutableElement) element;
          if (executableElement.getModifiers().containsAll(PUBLIC_ABSTRACT)) {
            MethodSpec template = MethodSpec.overriding(executableElement).build();

            StringBuilder statement = new StringBuilder();
            if (!template.returnType.equals(TypeName.VOID)) {
              statement.append("return ");
            }

            statement.append("this.$L.$N(");
            for (Iterator<ParameterSpec> iter = template.parameters.iterator(); iter.hasNext(); ) {
              statement.append(iter.next().name);
              if (iter.hasNext()) {
                statement.append(", ");
              }
            }
            statement.append(")");

            forwarderBuilder.addMethod(
                template.toBuilder()
                    .addStatement(statement.toString(), forwardedTypeInfo.delegateName, template)
                    .build());
          }
        }
      }
    }

    String packageName = getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();
    JavaFile forwarderFile =
        JavaFile.builder(packageName, forwarderBuilder.build())
            .skipJavaLangImports(true)
            .indent("  ")
            .build();
    forwarderFile.writeTo(processingEnv.getFiler());
    note(String.format("Wrote forwarder %s.%s", packageName, forwarderName), typeElement);
  }

  private List<ForwardedTypeInfo> createForwardedTypeInfos(Iterable<TypeElement> forwardedTypes) {
    List<ForwardedTypeInfo> forwardedTypeInfos = new LinkedList<>();
    for (TypeElement forwardedType : forwardedTypes) {
      TypeName forwardedTypeName = getTypeName(forwardedType);

      Name name = forwardedType.getSimpleName();
      char initChar = Character.toLowerCase(name.charAt(0));
      String delegateName;
      if (name.length() == 1) {
        delegateName = Character.toString(initChar);
      } else {
        delegateName = String.format("%c%s", initChar, name.subSequence(1, name.length()));
      }

      forwardedTypeInfos.add(new ForwardedTypeInfo(forwardedType, forwardedTypeName, delegateName));
    }
    return forwardedTypeInfos;
  }

  private static TypeName getTypeName(TypeElement typeElement) {
    ClassName rawTypeName = ClassName.get(typeElement);
    List<? extends TypeParameterElement> typeParameters = typeElement.getTypeParameters();
    if (typeParameters.isEmpty()) {
      return rawTypeName;
    } else {
      TypeName[] typeArguments = new TypeName[typeParameters.size()];
      for (int i = 0, max = typeArguments.length; i < max; i++) {
        typeArguments[i] = TypeVariableName.get(typeParameters.get(i));
      }
      return ParameterizedTypeName.get(rawTypeName, typeArguments);
    }
  }

  private AnnotationMirror getForwardAnnotation(Element element) {
    TypeElement forwardAnnotationTypeElement = getForwardAnnotationTypeElement();
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      if (annotationMirror.getAnnotationType().asElement().equals(forwardAnnotationTypeElement)) {
        return annotationMirror;
      }
    }
    throw new IllegalStateException();
  }

  private TypeElement getForwardAnnotationTypeElement() {
    return getElementUtils().getTypeElement(Forward.class.getName());
  }

  private Elements getElementUtils() {
    return processingEnv.getElementUtils();
  }

  private void error(String message, Element element) {
    log(Diagnostic.Kind.ERROR, message, element);
  }

  private void note(String message, Element element) {
    log(Diagnostic.Kind.NOTE, message, element);
  }

  private void log(Diagnostic.Kind kind, String message, Element element) {
    AnnotationMirror annotation = element == null ? null : getForwardAnnotation(element);
    processingEnv.getMessager().printMessage(kind, message, element, annotation);
  }
}
