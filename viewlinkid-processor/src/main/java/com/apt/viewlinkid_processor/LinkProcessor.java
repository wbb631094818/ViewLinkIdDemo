package com.apt.viewlinkid_processor;

import com.apt.viewlink.ViewLink;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

@AutoService(Processor.class)
public class LinkProcessor extends AbstractProcessor {

    private Filer mFiler;//文件类
    private Messager mMessager;//打印错误信息
    // 绑定activity及view集合  一个activity对应多个view
    private static final Map<TypeElement, List<ViewInfo>> viewlinkmap = new HashMap<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        mFiler = processingEnv.getFiler();
        mMessager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        //获取注解的所有元素
        Set<? extends Element> elements = roundEnvironment.getElementsAnnotatedWith(ViewLink.class);
        // 循环遍历所有的使用注解的view
        for (Element element : elements) {
            if (element instanceof VariableElement) {
                VariableElement variableElement = (VariableElement) element;
                Set<Modifier> modifiers = variableElement.getModifiers(); // 权限修饰符
                if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.PROTECTED)) {
                    // 类型检查 ，如果是私有的，或者PROTECTED，则退出
                    mMessager.printMessage(Diagnostic.Kind.ERROR, "成员变量的类型不能是PRIVATE或者PROTECTED");
                    return false;
                }
                saveViewInfo(variableElement);
            }
        }
        // 保存完所有的注解数据之后，我们去对每个activity里的view创建一个新的JAVA文件
        writeToFile();

        return false;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        // 返回所有需要监听处理的注解集合
        Set<String> strings = new HashSet<>();
        strings.add(ViewLink.class.getCanonicalName());
        return strings;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        // 返回支持的源码版本
        return SourceVersion.latestSupported();
    }

    /**
     * 保存每个activity对应的view集合
     *
     * @param variableElement
     */
    private void saveViewInfo(VariableElement variableElement) {
        System.out.println("variableElement: " + variableElement.toString());
        //获得外部元素对象   com.apt.viewlinkiddemo.MainActivity
        TypeElement typeElement = (TypeElement) variableElement.getEnclosingElement();

//        System.out.println("typeElement: " + typeElement.toString());
//        System.out.println("getQualifiedName: " + typeElement.getQualifiedName());
//        System.out.println("getNestingKind: " + typeElement.getNestingKind());
//        System.out.println("getSuperclass: " + typeElement.getSuperclass());
//        isActivity(typeElement.asType());


        // activity 对应的view集合
        List<ViewInfo> viewInfos;
        if (viewlinkmap.get(typeElement) != null) {
            // 如果不为null，说明该activity之前有关联的view，我们这时候把这个view集合拿出，再去把这个view也添加进去
            viewInfos = viewlinkmap.get(typeElement);
        } else {
            // 没有就新创建一个
            viewInfos = new ArrayList<>();
        }
        ViewLink annotation = variableElement.getAnnotation(ViewLink.class);
        // 获取注解的ID
        int viewId = annotation.value();
        // 获取变量名
        String viewName = variableElement.getSimpleName().toString();
        viewInfos.add(new ViewInfo(viewId, viewName));
        // 添加或者替换view集合
        viewlinkmap.put(typeElement, viewInfos);

    }

    /**
     * 生成文件
     */
    private void writeToFile() {
        Set<TypeElement> typeElements = viewlinkmap.keySet();
        // 方法参数
        String paramName = "activity";
        System.out.println("viewlinkmap: "+viewlinkmap.size());
        for (TypeElement typeElement : typeElements) {
            ClassName className = ClassName.get(typeElement);//获取参数类型
            PackageElement packageElement = (PackageElement) typeElement.getEnclosingElement();//获得外部对象
            String packageName = packageElement.getQualifiedName().toString();//获得外部类的包名 例：com.apt.viewlinkiddemo
            List<ViewInfo> viewInfos = viewlinkmap.get(typeElement);
            //代码块对象   就是这玩意：activity.textview = activity.findViewById(2131231103);
            //                      activity.bt = activity.findViewById(2131230807);
            CodeBlock.Builder builder = CodeBlock.builder();
            for (ViewInfo viewInfo : viewInfos) {
                //生成代码
                builder.add(paramName + "." + viewInfo.getViewName() + " = " + paramName + ".findViewById(" + viewInfo.getViewId() + ");\n");
            }

            // 成员变量 我们这里不需要
//            FieldSpec fieldSpec = FieldSpec.builder(String.class, "name", Modifier.PRIVATE).build();

            // 添加构造方法
            MethodSpec methodSpec = MethodSpec.constructorBuilder()//生成的方法对象
                    .addModifiers(Modifier.PUBLIC)//方法的修饰符
                    .addParameter(className, paramName)//方法中的参数，第一个是参数类型（例：MainActivity），第二个是参数名
                    .addCode(builder.build())//方法体重的代码
                    .build();

//            // 添加普通方法
//            MethodSpec methodSpec2 = MethodSpec.methodBuilder("commonMethodName")//生成的方法对象
//                    .addModifiers(Modifier.PUBLIC)//方法的修饰符
//                    .addParameter(className, paramName)//方法中的参数，第一个是参数类型（例：MainActivity），第二个是参数名
//                    .addCode(builder.build())//方法体重的代码
//                    .build();
            // 添加类
            TypeSpec typeSpec = TypeSpec.classBuilder(typeElement.getSimpleName().toString() + ViewLink.SUFFIX)//类对象，参数：类名
                    .addMethod(methodSpec)//添加方法
//                    .addMethod(methodSpec2) // 添加方法二
//                    .addField(fieldSpec)//添加成员变量，我们这里不需要
                    .build();

            //javaFile对象，最终用来写入的对象，参数1：包名；参数2：TypeSpec
            JavaFile javaFile = JavaFile.builder(packageName, typeSpec).build();

            try {
                javaFile.writeTo(mFiler);//写入文件
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * 判断一个类型是否为Activity
     *
     * class: com.apt.viewlinkiddemo.BaseActivity
     * class: androidx.appcompat.app.AppCompatActivity
     * class: androidx.fragment.app.FragmentActivity
     * class: androidx.activity.ComponentActivity
     * class: androidx.core.app.ComponentActivity
     * class: android.app.Activity
     * @param typeMirror  获得外部元素对象的类路径 例：com.apt.viewlinkiddemo.MainActivity
     * @return
     */
    private boolean isActivity(TypeMirror typeMirror) {
        Types typeUtils = processingEnv.getTypeUtils();
        List<? extends TypeMirror> typeMirrors = typeUtils.directSupertypes(typeMirror);
        for (TypeMirror mirror : typeMirrors) {
            System.out.println("class: "+mirror.toString());
            if ("android.app.Activity".equals(mirror.toString())) {
                return true;
            }
            if (isActivity(mirror)) {
                return true;
            }
        }
        return false;
    }
}
