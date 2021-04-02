

> 如今很多的第三方库都使用了注解的方式，像ButterKnife，room等。在看其源码的过程中，对注解产生了兴趣，所以就有了这篇。本文以自己实现简单的ButterKnife功能为例，来慢慢熟悉自定义AbstractProcessor。

>**文章仅供学习参考**

# ButterKnife例子
在写之前，我们得大概知道实现这个功能的大概原理及步骤，该例子也是同理
## 原理
在编译过程中，获取到注解的内容，生成JAVA文件及代码，再通过反射机制调用JAVA文件里的方法。
简单来说就是：我自动帮你把**findViewById**写了
类似这样：
```java
package com.apt.viewlinkiddemo;

class MainActivity_ViewLink {
  public MainActivity_ViewLink(MainActivity activity) {
    activity.textview = activity.findViewById(2131231103);
    activity.bt = activity.findViewById(2131230807);
  }
}
```

## 步骤
### 1. 创建两个JAVA依赖库库
![JAVA依赖库](https://img-blog.csdnimg.cn/20210401142443958.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2ppdXNoaTE5OTU=,size_16,color_FFFFFF,t_70)

>  **viewlink**  为所有注解的代码 
>  **viewlinkid-processor**为处理这些注解的自定义AbstractProcessor的代码

######  1. 为什么是JAVA依赖库而不是Android的依赖库？
AbstractProcessor  类只有JAVA有，Android库是没有的。并且viewlinkid-processor需要依赖viewlink，所以viewlink必须要是JAVA库

######  2. 为什么要分成两个库而不是在一个库？
viewlinkid-processor 库里的代码只在代码编译的时候运行，所以没必要和其他库一起打包到apk中，所以分成俩库。

### 2. 定义注解
 在viewlink库中，添加一个类ViewLink

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)  // 字段、枚举的常量,或者变量
public @interface ViewLink {
    String SUFFIX = "_ViewLink"; // 生成文件后缀
    int value(); // 控件ID
}
```
所有注解含义：

```java
  @Retention：注解的保留位置　　　　　　　　　
    @Retention(RetentionPolicy.SOURCE)   //注解仅存在于源码中，在class字节码文件中不包含
    @Retention(RetentionPolicy.CLASS)     // 默认的保留策略，注解会在class字节码文件中存在，但运行时无法获得，
    @Retention(RetentionPolicy.RUNTIME)  // 注解会在class字节码文件中存在，在运行时可以通过反射获取到
  @Target:注解的作用目标
    @Target(ElementType.TYPE)   //接口、类、枚举
    @Target(ElementType.FIELD) //字段、枚举的常量
    @Target(ElementType.METHOD) //方法
    @Target(ElementType.PARAMETER) //方法参数
    @Target(ElementType.CONSTRUCTOR)  //构造函数
    @Target(ElementType.LOCAL_VARIABLE)//局部变量
    @Target(ElementType.ANNOTATION_TYPE)//注解
    @Target(ElementType.PACKAGE) ///包   
  @Document：说明该注解将被包含在javadoc中
  @Inherited：说明子类可以继承父类中的该注解
```

 ### 3. 处理注解 -- 核心
 定义好注解后，我们就需要去处理，在新建的viewlinkid-processor库中，
 

 #### 1.  引用一些库
 

```java
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    implementation 'com.squareup:javapoet:1.13.0'//JavaPoet 编写代码使用，能帮我们简单的生成JAVA类及方法等
    implementation 'com.google.auto.service:auto-service:1.0-rc7'
    annotationProcessor 'com.google.auto.service:auto-service:1.0-rc7'//auto-service 注册Processor，google的自动注解处理器
    implementation project(path: ':viewlink')
}
```
####  2.  新建自定义注解处理器LinkProcessor
 

```java
@AutoService(Processor.class)
public class LinkProcessor extends AbstractProcessor {

    private Filer mFiler;//文件类，生成JAVA文件的
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
```
上面为该类的所有实现，接下来一点点分析
```java
@AutoService(Processor.class)
该注解为谷歌的自动注解器，就是我们上面引用的那个第三方库auto-service的，
它帮助我们简单的生成了如下图所示的文件
```
![注解器](https://img-blog.csdnimg.cn/20210401145026319.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2ppdXNoaTE5OTU=,size_16,color_FFFFFF,t_70)
里面内容就一句是：

```java
com.apt.viewlinkid_processor.LinkProcessor
```

该类继承了AbstractProcessor，主要方法为：

```java
    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        mFiler = processingEnv.getFiler();
        mMessager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        //这是核心方法，是处理所以注解的核心方法
       
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

```
getSupportedAnnotationTypes及getSupportedSourceVersion方法也可以使用注解的方式，如：

```java
@SupportedAnnotationTypes("com.apt.viewlink.ViewLink")
@SupportedSourceVersion(SourceVersion.RELEASE_7)
```

接下来重点是**process**方法
该方法主要为获取所有的注解信息，动态生成JAVA代码。具体的看上面代码示例，都有注释。
 
### 4. 使用

####  1. 集成
```java
    implementation project(path: ':viewlink')
    annotationProcessor project(':viewlinkid-processor')
```

#### 2. 工具方法
  新建一个ViewLinkUtil类

```java
public class ViewLinkUtil {

    /**
     * 绑定Activity
     * */
    public static void link(Activity activity) {
        if (activity == null) {
            return;
        }
        String activityName = activity.getClass().getName();//获取类的全限定名
        ClassLoader classLoader = activity.getClass().getClassLoader();//获得类加载器
        try {
            Class<?> loadClass = classLoader.loadClass(activityName + ViewLink.SUFFIX);//加载类
            Constructor<?> constructor = loadClass.getConstructor(activity.getClass());
            constructor.newInstance(activity);//调用其构造方法
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```
该方法就是通过获取该activity的名称动态拼接成我们生成的文件类名，调用我们生成类里的构造函数，实现给每个控件findViewById
#### 3. 使用

```java
public class MainActivity extends BaseActivity {

    @ViewLink(R.id.textview)
    public TextView textview;

    @ViewLink(R.id.bt)
    public Button bt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewLinkUtil.link(this);
    }
}
```
#### 4. 编译
 编译后我们会生成一个类，类里有我们写入的方法及代码块，例：
 ![在这里插入图片描述](https://img-blog.csdnimg.cn/20210401161541715.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2ppdXNoaTE5OTU=,size_16,color_FFFFFF,t_70)
内容为：

```java
package com.apt.viewlinkiddemo;

class MainActivity_ViewLink {
  public MainActivity_ViewLink(MainActivity activity) {
    activity.textview = activity.findViewById(2131231103);
    activity.bt = activity.findViewById(2131230807);
  }
}
```

## 扩展
#### 1. AbstractProcessor是在编译时的哪个环节开始进行的？
java的编译流程
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210401162255440.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2ppdXNoaTE5OTU=,size_16,color_FFFFFF,t_70)
上图是一张简单的编译流程图，compiler代表我们的javac(java语言编程编译器)。这张图应该中其实缺少了一个流程，在source -> complier的过程中就应该把我们的Processor补充上去。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210401162318895.png)
 把两张图结合就是整个java的编译流程了。整个编译过程就是 source(源代码) -> processor（处理器） -> generate （文件生成）-> javacompiler -> .class文件 -> .dex(只针对安卓)。
#### 2. 如何判断你获取的注解的类继承自activity？

```java

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
```
传入外部类对象TypeElement.asType() 通过递归的方式不断查找该类继承的上一个类，直到你判断出等于android.app.Activity就说明该类继承自activity


## 参考
[聊聊AbstractProcessor和Java编译流程](https://cloud.tencent.com/developer/article/1717461)
[annotationProcessor的二三事](https://27house.cn/archives/1441)

