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
package org.jetbrains.kotlin.filesystem;

import com.google.common.collect.Lists;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jetbrains.kotlin.builder.KotlinPsiManager;
import org.jetbrains.kotlin.filesystem.lightclasses.LightClassFile;
import org.jetbrains.kotlin.model.KotlinEnvironment;
import org.jetbrains.kotlin.utils.ProjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.fileClasses.FileClasses;
import org.jetbrains.kotlin.fileClasses.NoResolveFileClassesProvider;
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtSecondaryConstructor;
import org.jetbrains.kotlin.psi.KtVisitorVoid;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class KotlinLightClassManager {
    
    private final org.netbeans.api.project.Project project;
    
    private final ConcurrentMap<File, Set<FileObject>> sourceFiles = new ConcurrentHashMap<>();
    
    @NotNull
    public static KotlinLightClassManager getInstance(@NotNull org.netbeans.api.project.Project project){
        Project ideaProject = KotlinEnvironment.getEnvironment(project).getProject();
        return ServiceManager.getService(ideaProject, KotlinLightClassManager.class);
    }
    
    public KotlinLightClassManager(@NotNull org.netbeans.api.project.Project project){
        this.project = project;
    }
    
    public void computeLightClassesSources(){
        Map<File, Set<FileObject>> newSourceFilesMap = new HashMap<>();
        for (FileObject sourceFile : KotlinPsiManager.INSTANCE.getFilesByProject(project)){
            List<String> lightClassesPaths = getLightClassesPaths(sourceFile);
            
            for (String path : lightClassesPaths){
                LightClassFile lightClassFile = new LightClassFile(project, path);
                Set<FileObject> newSourceFiles = newSourceFilesMap.get(lightClassFile.asFile());
                if (newSourceFiles == null){
                    newSourceFiles = new HashSet<>();
                    newSourceFilesMap.put(lightClassFile.asFile(), newSourceFiles);
                }
                newSourceFiles.add(sourceFile);
            }
        }
        
        sourceFiles.clear();
        sourceFiles.putAll(newSourceFilesMap);
    }

    @NotNull
    public List<String> getLightClassesPaths(FileObject sourceFile) {
        List<String> lightClasses = new ArrayList<>();
        
        KtFile ktFile = ProjectUtils.getKtFile(sourceFile);
        for (KtClassOrObject classOrObject : findLightClasses(ktFile)){
            String internalName = getInternalName(classOrObject);
            if (internalName != null){
                lightClasses.add(computePathByInternalName(internalName));
            }
        }
        
        if (PackagePartClassUtils.fileHasTopLevelCallables(ktFile)){
            String newFacadeInternalName = FileClasses.getFileClassInternalName(
                    NoResolveFileClassesProvider.INSTANCE, ktFile);
            lightClasses.add(computePathByInternalName(newFacadeInternalName));
        }
        
        return lightClasses;
    }

    private List<KtClassOrObject> findLightClasses(@NotNull KtFile ktFile) {
        final List<KtClassOrObject> lightClasses = new ArrayList<>();
        ktFile.acceptChildren(new KtVisitorVoid(){
            @Override
            public void visitClassOrObject(@NotNull KtClassOrObject classOrObject){
                lightClasses.add(classOrObject);
                super.visitClassOrObject(classOrObject);
            }
            
            @Override
            public void visitNamedFunction(@NotNull KtNamedFunction function) {
            }
            
            @Override
            public void visitSecondaryConstructor(@NotNull KtSecondaryConstructor constructor) {
            }
            
            @Override
            public void visitProperty(@NotNull KtProperty property) {
            }
            
            @Override
            public void visitElement(@Nullable PsiElement element) {
                if (element != null) {
                    element.acceptChildren(this);
                }
            }
        });
        
        
        return lightClasses;
    }

    private String computePathByInternalName(String internalName) {
        StringBuilder builder = new StringBuilder();
        builder.append(internalName).append(".class");
        return builder.toString();
    }

    public void updateLightClasses(@NotNull Set<FileObject> affectedFiles) {
        for (Map.Entry<File, Set<FileObject>> entry : sourceFiles.entrySet()){
            FileObject lightClassFileObject = FileUtil.toFileObject(entry.getKey());
            if (lightClassFileObject == null){
                continue;
            }
            
            LightClassFile lightClassFile = new LightClassFile(lightClassFileObject);
            
            for (FileObject sourceFile : entry.getValue()){
                if (affectedFiles.contains(sourceFile)){
                    lightClassFile.refreshFile();
                    break;
                }
            }
        }
    }
    
    public List<KtFile> getSourceFiles(@NotNull File file){
        if (sourceFiles.isEmpty()){
            computeLightClassesSources();
        }
        
        return getSourceKtFiles(file);
    }

    @NotNull
    private List<KtFile> getSourceKtFiles(File file) {
        Set<FileObject> sourceIOFiles = sourceFiles.get(file);
        if (sourceIOFiles != null){
            List<KtFile> ktSourceFiles = Lists.newArrayList();
            for (FileObject sourceFile : sourceIOFiles){
                KtFile ktFile = ProjectUtils.getKtFile(sourceFile);
                if (ktFile != null){
                    ktSourceFiles.add(ktFile);
                }
            }
            
            return ktSourceFiles;
        }
        
        return Collections.<KtFile>emptyList();
    }
    
    @Nullable
    public static String getInternalName(KtClassOrObject classOrObject) {
        FqName fullFqName = classOrObject.getFqName();
        if (fullFqName == null) return null;
        
        KtClassOrObject topmostClassOrObject = PsiTreeUtil.getTopmostParentOfType(classOrObject, KtClassOrObject.class);
        if (topmostClassOrObject == null) return makeInternalByToplevel(fullFqName);
        
        FqName topLevelFqName = topmostClassOrObject.getFqName();
        if (topLevelFqName == null) return null;
        
        String nestedPart = fullFqName.asString().substring(topLevelFqName.asString().length()).replaceAll("\\.", "\\$");
        
        return makeInternalByToplevel(topLevelFqName) + nestedPart;
    }
    
    private static String makeInternalByToplevel(FqName fqName) {
        return fqName.asString().replaceAll("\\.", "/");
    }
}
