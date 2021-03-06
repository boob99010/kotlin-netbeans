/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package org.jetbrains.kotlin.model;

import org.jetbrains.kotlin.resolve.lang.kotlin.NetBeansVirtualFileFinder;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade;
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport;
import org.jetbrains.kotlin.cli.jvm.compiler.CliLightClassGenerationSupport;
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension;
import org.jetbrains.kotlin.extensions.ExternalDeclarationsProvider;
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache;
import org.jetbrains.kotlin.parsing.KotlinParserDefinition;
import org.jetbrains.kotlin.resolve.CodeAnalyzerInitializer;

import com.intellij.codeInsight.ContainerProvider;
import com.intellij.codeInsight.NullableNotNullManager;
import com.intellij.codeInsight.runner.JavaMainMethodProvider;
import com.intellij.core.CoreApplicationEnvironment;
import com.intellij.core.CoreJavaFileManager;
import com.intellij.core.JavaCoreApplicationEnvironment;
import com.intellij.core.JavaCoreProjectEnvironment;
import com.intellij.mock.MockProject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.extensions.ExtensionsArea;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.impl.ZipHandler;
import com.intellij.psi.PsiElementFinder;
import com.intellij.psi.PsiManager;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import com.intellij.psi.compiled.ClassFileDecompilers;
import com.intellij.psi.impl.PsiTreeChangePreprocessor;
import com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy;
import com.intellij.psi.impl.file.impl.JavaFileManager;
import java.util.Collections;
import org.jetbrains.kotlin.filesystem.KotlinLightClassManager;
import org.jetbrains.kotlin.projectsextensions.KotlinProjectHelper;
import org.jetbrains.kotlin.resolve.BuiltInsReferenceResolver;
import org.jetbrains.kotlin.resolve.KotlinCacheServiceImpl;
import org.jetbrains.kotlin.resolve.KotlinSourceIndex;
import org.jetbrains.kotlin.utils.ProjectUtils;
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService;
import org.jetbrains.kotlin.cli.common.CliModuleVisibilityManagerImpl;
import org.jetbrains.kotlin.codegen.extensions.ClassBuilderInterceptorExtension;
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages;
import com.intellij.formatting.KotlinLanguageCodeStyleSettingsProvider;
import com.intellij.formatting.KotlinSettingsProvider;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import org.jetbrains.kotlin.cli.jvm.compiler.JavaRoot;
import org.jetbrains.kotlin.idea.KotlinFileType;
import org.jetbrains.kotlin.js.resolve.diagnostics.DefaultErrorMessagesJs;
import org.jetbrains.kotlin.load.kotlin.JvmVirtualFileFinderFactory;
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager;
import org.jetbrains.kotlin.log.KotlinLogger;
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticSuppressor;
import org.jetbrains.kotlin.resolve.diagnostics.SuppressStringProvider;
import org.jetbrains.kotlin.resolve.jvm.diagnostics.DefaultErrorMessagesJvm;
import org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension;
import org.openide.util.Exceptions;

/**
 * This class creates Kotlin environment for Kotlin project.
 * @author РђР»РµРєСЃР°РЅРґСЂ
 */
@SuppressWarnings("deprecation")
public class KotlinEnvironment {
    public final static String KOTLIN_COMPILER_PATH = ProjectUtils.buildLibPath("kotlin-compiler");
    
    private static final Map<org.netbeans.api.project.Project, KotlinEnvironment> CACHED_ENVIRONMENT =
            new HashMap<org.netbeans.api.project.Project, KotlinEnvironment>();
    private static final Object ENVIRONMENT_LOCK = new Object(){};
    
    
    private final JavaCoreApplicationEnvironment applicationEnvironment;
    private final JavaCoreProjectEnvironment projectEnvironment;
    private final MockProject project;
    
    private final Set<JavaRoot> roots = new LinkedHashSet<JavaRoot>();
    
    private KotlinEnvironment(@NotNull org.netbeans.api.project.Project kotlinProject, @NotNull Disposable disposable) {
        applicationEnvironment = createJavaCoreApplicationEnvironment(disposable);
        projectEnvironment = new JavaCoreProjectEnvironment(disposable, applicationEnvironment) {
            @Override
            protected void preregisterServices() {
                registerProjectExtensionPoints(Extensions.getArea(getProject()));
            }
        };
        
        project = projectEnvironment.getProject();

        project.registerService(ModuleVisibilityManager.class, new CliModuleVisibilityManagerImpl());
        
        project.registerService(NullableNotNullManager.class, new KotlinNullableNotNullManager(kotlinProject)); 
        
        PsiManager psiManager = project.getComponent(PsiManager.class);
        assert (psiManager != null);
        project.registerService(CoreJavaFileManager.class,
                (CoreJavaFileManager) ServiceManager.getService(project, JavaFileManager.class));
        
        CliLightClassGenerationSupport cliLightClassGenerationSupport = new CliLightClassGenerationSupport(project);
        project.registerService(LightClassGenerationSupport.class, cliLightClassGenerationSupport);
        project.registerService(CliLightClassGenerationSupport.class, cliLightClassGenerationSupport);
        project.registerService(KtLightClassForFacade.FacadeStubCache.class, new KtLightClassForFacade.FacadeStubCache(project));
        project.registerService(CodeAnalyzerInitializer.class, cliLightClassGenerationSupport);
        project.registerService(KotlinLightClassManager.class, new KotlinLightClassManager(kotlinProject));
        project.registerService(BuiltInsReferenceResolver.class, new BuiltInsReferenceResolver(project));
        project.registerService(KotlinSourceIndex.class, new KotlinSourceIndex());
        project.registerService(KotlinCacheService.class, new KotlinCacheServiceImpl(project, kotlinProject));
        
        configureClasspath(kotlinProject);
        
        project.registerService(JvmVirtualFileFinderFactory.class, new NetBeansVirtualFileFinder(kotlinProject));
        
        ExternalDeclarationsProvider.Companion.registerExtensionPoint(project);
        ExpressionCodegenExtension.Companion.registerExtensionPoint(project);
        
        registerApplicationExtensionPointsAndExtensionsFrom();
        
//        for (String config : EnvironmentConfigFiles.JVM_CONFIG_FILES) {
//            registerApplicationExtensionPointsAndExtensionsFromConfigFile(config);
//        }

        CACHED_ENVIRONMENT.put(kotlinProject, KotlinEnvironment.this);
        
    }
    
    private static void registerProjectExtensionPoints(ExtensionsArea area) {
        CoreApplicationEnvironment.registerExtensionPoint(area, PsiTreeChangePreprocessor.EP_NAME, PsiTreeChangePreprocessor.class);
        CoreApplicationEnvironment.registerExtensionPoint(area, PsiElementFinder.EP_NAME, PsiElementFinder.class);
    }
    
    private static void getExtensionsFromCommonXml() {
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                new ExtensionPointName<DiagnosticSuppressor>("org.jetbrains.kotlin.diagnosticSuppressor"), DiagnosticSuppressor.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                new ExtensionPointName<DefaultErrorMessages.Extension>("org.jetbrains.kotlin.defaultErrorMessages"), DefaultErrorMessages.Extension.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                new ExtensionPointName<SuppressStringProvider>("org.jetbrains.kotlin.suppressStringProvider"), SuppressStringProvider.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                new ExtensionPointName<ExternalDeclarationsProvider>("org.jetbrains.kotlin.externalDeclarationsProvider"), ExternalDeclarationsProvider.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                new ExtensionPointName<ExpressionCodegenExtension>(("org.jetbrains.kotlin.expressionCodegenExtension")), ExpressionCodegenExtension.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                new ExtensionPointName<ClassBuilderInterceptorExtension>(("org.jetbrains.kotlin.classBuilderFactoryInterceptorExtension")), ClassBuilderInterceptorExtension.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                new ExtensionPointName<PackageFragmentProviderExtension>(("org.jetbrains.kotlin.packageFragmentProviderExtension")), PackageFragmentProviderExtension.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(CodeStyleSettingsProvider.EXTENSION_POINT_NAME, KotlinSettingsProvider.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(LanguageCodeStyleSettingsProvider.EP_NAME, KotlinLanguageCodeStyleSettingsProvider.class);
        Extensions.getRootArea().getExtensionPoint(CodeStyleSettingsProvider.EXTENSION_POINT_NAME).registerExtension(new KotlinSettingsProvider());
        Extensions.getRootArea().getExtensionPoint(LanguageCodeStyleSettingsProvider.EP_NAME).registerExtension(new KotlinLanguageCodeStyleSettingsProvider());
        Extensions.getRootArea().getExtensionPoint(DefaultErrorMessages.Extension.EP_NAME).registerExtension(new DefaultErrorMessagesJvm());
        Extensions.getRootArea().getExtensionPoint(DefaultErrorMessages.Extension.EP_NAME).registerExtension(new DefaultErrorMessagesJs());
    }
    
    private static void getExtensionsFromKotlin2JvmXml() {
        CoreApplicationEnvironment.registerComponentInstance(Extensions.getRootArea().getPicoContainer(), 
                DefaultErrorMessages.Extension.class, new DefaultErrorMessagesJvm());
    }
    
    private static void registerApplicationExtensionPointsAndExtensionsFromConfigFile(String configFilePath) {
        File pluginRoot = new File(KOTLIN_COMPILER_PATH);
        CoreApplicationEnvironment.registerExtensionPointAndExtensions(pluginRoot, configFilePath, Extensions.getRootArea());
    }
    
    private static void registerApplicationExtensionPointsAndExtensionsFrom(/*String configFilePath*/) {
        getExtensionsFromCommonXml();
        getExtensionsFromKotlin2JvmXml();
    }
    
    @NotNull
    public static KotlinEnvironment getEnvironment(@NotNull org.netbeans.api.project.Project kotlinProject) {
        synchronized (ENVIRONMENT_LOCK) {
            if (!CACHED_ENVIRONMENT.containsKey(kotlinProject)) {
                CACHED_ENVIRONMENT.put(kotlinProject, new KotlinEnvironment(kotlinProject, Disposer.newDisposable()));
            }
            
            return CACHED_ENVIRONMENT.get(kotlinProject);
        }
    }
    
    public static void updateKotlinEnvironment(@NotNull org.netbeans.api.project.Project kotlinProject) {
        synchronized (ENVIRONMENT_LOCK) {
            if (CACHED_ENVIRONMENT.containsKey(kotlinProject)) {
                KotlinEnvironment environment = CACHED_ENVIRONMENT.get(kotlinProject);
                Disposer.dispose(environment.getJavaApplicationEnvironment().getParentDisposable());
                ZipHandler.clearFileAccessorCache();
            }
            CACHED_ENVIRONMENT.put(kotlinProject, new KotlinEnvironment(kotlinProject, Disposer.newDisposable()));
        }
    }

    @NotNull
    public JavaCoreApplicationEnvironment getJavaApplicationEnvironment() {
        return applicationEnvironment;
    }
    
    private void configureClasspath(@NotNull org.netbeans.api.project.Project kotlinProject) {
        Set<String> classpath = ProjectUtils.getClasspath(kotlinProject);
        String lightClassesDir = KotlinProjectHelper.INSTANCE.getLightClassesDirectory(kotlinProject).toURI().toString();
        KotlinLogger.INSTANCE.logInfo("Project " + kotlinProject.getProjectDirectory().getPath() +
                " classpath is: " + classpath);
        for (String s : classpath) {
            if (s.endsWith("!/")){
                addToClasspath(s.split("!/")[0].split("file:")[1], null);
            } else {
                if (!lightClassesDir.contains(s)){
                    addToClasspath(s, null);
                }
            }
        }
    }
    
    private JavaCoreApplicationEnvironment createJavaCoreApplicationEnvironment(@NotNull Disposable disposable) {
        Extensions.cleanRootArea(disposable);
        registerAppExtensionPoints();
        JavaCoreApplicationEnvironment javaApplicationEnvironment = new JavaCoreApplicationEnvironment(disposable);
        
        // ability to get text from annotations xml files
        javaApplicationEnvironment.registerFileType(PlainTextFileType.INSTANCE, "xml");
        
        javaApplicationEnvironment.registerFileType(KotlinFileType.INSTANCE, "kt");
        javaApplicationEnvironment.registerFileType(KotlinFileType.INSTANCE, "jet");
        javaApplicationEnvironment.registerFileType(KotlinFileType.INSTANCE, "ktm");
        
        javaApplicationEnvironment.registerParserDefinition(new KotlinParserDefinition());
        
        javaApplicationEnvironment.getApplication().registerService(KotlinBinaryClassCache.class,
                new KotlinBinaryClassCache());
        
        return javaApplicationEnvironment;
    }
    
    private static void registerAppExtensionPoints() {
        CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ContainerProvider.EP_NAME,
                ContainerProvider.class);
        CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ClsCustomNavigationPolicy.EP_NAME,
                ClsCustomNavigationPolicy.class);
        CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ClassFileDecompilers.EP_NAME,
                ClassFileDecompilers.Decompiler.class);
        
        // For j2k converter 
        CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), PsiAugmentProvider.EP_NAME, PsiAugmentProvider.class);
        CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), JavaMainMethodProvider.EP_NAME, JavaMainMethodProvider.class);
    }
    
    @NotNull
    public Project getProject() {
        return project;
    }
    
    private void addToClasspath(String path, JavaRoot.RootType rootType) {
        File file = new File(path);
        if (file.isFile()) {
            VirtualFile jarFile = applicationEnvironment.getJarFileSystem().findFileByPath(path+"!/");
            
            if (jarFile == null) {
                return;
            }
            
            projectEnvironment.addJarToClassPath(file);
            
            JavaRoot.RootType type = rootType;
            if (type == null) {
                type = JavaRoot.RootType.BINARY;
            }
            roots.add(new JavaRoot(jarFile, type, null));
        } else {
            VirtualFile root = applicationEnvironment.getLocalFileSystem().findFileByPath(path);
            if (root == null) {
                return;
            }
            projectEnvironment.addSourcesToClasspath(root);
            
            JavaRoot.RootType type = rootType;
            if (type == null) {
                type = JavaRoot.RootType.SOURCE;
            }
            roots.add(new JavaRoot(root, type, null));
        }
    }
    
    public boolean isJarFile(@NotNull String pathToJar){
        VirtualFile jarFile = applicationEnvironment.getJarFileSystem().findFileByPath(pathToJar + "!/");
        return jarFile != null && jarFile.isValid();
    }

    @Nullable
    public VirtualFile getVirtualFile(@NotNull String location) { 
        return applicationEnvironment.getLocalFileSystem().findFileByPath(location);
    }
    
    public VirtualFile getVirtualFileInJar(@NotNull String path) {
        String decodedPath;
        try {
            decodedPath = URLDecoder.decode(path, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            decodedPath = path;
            Exceptions.printStackTrace(ex);
        }
        VirtualFile file = applicationEnvironment.getJarFileSystem().findFileByPath(decodedPath);
        return file;
    }
    
    public VirtualFile getVirtualFileInJar(@NotNull String pathToJar, @NotNull String relativePath) {
        String decodedPathToJar = pathToJar; 
        String decodedRelativePath = relativePath;
        try {
            decodedPathToJar = URLDecoder.decode(pathToJar, "UTF-8");
            decodedRelativePath = URLDecoder.decode(relativePath, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            Exceptions.printStackTrace(ex);
        }
        VirtualFile file = applicationEnvironment.getJarFileSystem().
                findFileByPath(decodedPathToJar + "!/" + decodedRelativePath);
        return file;
    }
    
    @NotNull
    public Set<JavaRoot> getRoots(){
        return Collections.unmodifiableSet(roots);
    }
    
}