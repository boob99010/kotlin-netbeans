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
package org.jetbrains.kotlin.utils;

import java.io.IOException;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.spi.project.support.ant.AntProjectHelper;
import org.netbeans.spi.project.support.ant.ProjectGenerator;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.modules.Places;
import org.openide.util.Exceptions;

public class KotlinMockProject {

    private Project project = null;
            
    public static KotlinMockProject INSTANCE = new KotlinMockProject();
    
    private KotlinMockProject() {}
    
    private AntProjectHelper createHelper() {
        FileObject userDirectory = FileUtil.toFileObject(Places.getUserDirectory());
        String projectName = "ktFilesWithoutProject";
        if (userDirectory.getFileObject(projectName) == null){
            try {
                userDirectory.createFolder(projectName);
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
        
        AntProjectHelper helper = null;
        
        try {
            helper =  ProjectGenerator.createProject(userDirectory, "org.netbeans.modules.java.j2seproject");
        } catch (IOException | IllegalArgumentException ex) {
            Exceptions.printStackTrace(ex);
        }
        
        return helper;
    } 
    
    public Project getMockProject() {
        if (project == null) {
            try {
                AntProjectHelper helper = createHelper();
                project = ProjectManager.getDefault().findProject(helper.getProjectDirectory());
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
        
        return project;
    }
    
}
