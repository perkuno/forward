package uno.perk.forward.apt;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
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
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import com.squareup.javapoet.ClassName;
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
    final TypeElement forwardedType;
    final String delegateName;
    final String forwarderPattern;

    ForwardMirror(TypeElement forwardedType, String delegateName, String forwarderPattern) {
      this.forwardedType = forwardedType;
      this.delegateName = delegateName;
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
      Matcher classNameMatcher =
          CLASSNAME_MATCHER.matcher(forwardMirror.forwardedType.getSimpleName());
      String forwarderName = classNameMatcher.replaceFirst(forwardMirror.forwarderPattern);

      try {
        generateForwarder(
            typeElement,
            forwardMirror.forwardedType,
            forwardMirror.delegateName,
            forwarderName);
      } catch (IOException e) {
        error(String.format("Failed to write forwarder: %s.", e), element);
      }
    }
    return true;
  }

  private ForwardMirror getForwardMirror(TypeElement typeElement) {
    TypeElement forwardedType = null;
    String delegateName = null;
    String forwarderPattern = null;

    AnnotationMirror forwardAnnotation = getForwardAnnotation(typeElement);
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        getElementUtils().getElementValuesWithDefaults(forwardAnnotation).entrySet()) {

      String keyName = entry.getKey().getSimpleName().toString();
      Object value = entry.getValue().getValue();
      if (keyName.equals("value")) {
        if (!(value instanceof DeclaredType)) {
          error("Unexpected value for 'value'.", typeElement);
        }
        forwardedType = (TypeElement) ((DeclaredType) value).asElement();
        if (!forwardedType.getKind().isInterface()) {
          error("@Forward can only forward interface types.", typeElement);
        }
      } else if (keyName.equals("delegateName")) {
        if (!(value instanceof String)) {
          error("Unexpected value for 'delegateName'.", typeElement);
        }
        delegateName = (String) value;
      } else if (keyName.equals("forwarderPattern")) {
        if (!(value instanceof String)) {
          error("Unexpected value for 'forwarderPattern'.", typeElement);
        }
        forwarderPattern = (String) value;
      } else {
        error(
            String.format("Unexpected annotation member %s = %s.", keyName, value),
            typeElement);
      }
    }

    if (forwardedType == null || delegateName == null || forwarderPattern == null) {
      error("Failed to extract values from Forward annotation.", typeElement);
    }

    return new ForwardMirror(forwardedType, delegateName, forwarderPattern);
  }

  private static final EnumSet<Modifier> PUBLIC_ABSTRACT =
      EnumSet.of(Modifier.PUBLIC, Modifier.ABSTRACT);

  private void generateForwarder(
      TypeElement typeElement,
      TypeElement forwardedType,
      String delegateName,
      String forwarderName) throws IOException {

    TypeSpec.Builder forwarderBuilder = TypeSpec.classBuilder(forwarderName);
    List<? extends TypeParameterElement> typeParameters = forwardedType.getTypeParameters();
    for (TypeParameterElement typeParameter : typeParameters) {
      TypeVariable typeVariable = (TypeVariable) typeParameter.asType();
      forwarderBuilder.addTypeVariable(TypeVariableName.get(typeVariable));
    }

    TypeName forwardedTypeName = getTypeName(forwardedType);
    forwarderBuilder
        .addSuperinterface(forwardedTypeName)
        .addField(forwardedTypeName, delegateName, Modifier.PROTECTED, Modifier.FINAL)
        .addMethod(
            MethodSpec.constructorBuilder()
                .addParameter(forwardedTypeName, delegateName)
                .addStatement("this.$L = $L", delegateName, delegateName)
                .build());

    for (Element element : getElementUtils().getAllMembers(forwardedType)) {
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
                  .addStatement(statement.toString(), delegateName, template)
                  .build());
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
    note(
        String.format("Wrote forwarder %s.%s for %s %s",
            packageName, forwarderName, delegateName, forwardedTypeName),
        typeElement);
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
