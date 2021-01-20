package com.baomidou.plugin.idea.mybatisx.alias;

import com.baomidou.plugin.idea.mybatisx.util.SpringStringUtils;
import com.baomidou.plugin.idea.mybatisx.util.StringUtils;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.module.JavaModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.spring.boot.model.SpringBootModelConfigFileContributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.composer.ComposerException;
import org.yaml.snakeyaml.parser.ParserException;
import org.yaml.snakeyaml.scanner.ScannerException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The type springboot Bean alias resolver.
 *
 * @author ls9527
 */
public class SpringBootPackageResolver extends PackageAliasResolver {

    private static final String YML = "yml";
    private static final String YAML = "yaml";
    private static final String PROPERTIES = "properties";
    public static final String REGEX = ",; ";

    /**
     * Instantiates a new Bean alias resolver.
     *
     * @param project the project
     */
    public SpringBootPackageResolver(Project project) {
        super(project);
    }

    @NotNull
    @Override
    public Collection<String> getPackages(@Nullable PsiElement element) {
        Set<String> pkgSet = new HashSet<>();
        ExtensionPointName<SpringBootModelConfigFileContributor> objectExtensionPointName = ExtensionPointName.create("com.intellij.spring.boot.modelConfigFileContributor");
        List<SpringBootModelConfigFileContributor> extensionList = objectExtensionPointName.getExtensionList();
        for (SpringBootModelConfigFileContributor extension : extensionList) {
            Collection<Module> modulesOfType = ModuleUtil.getModulesOfType(project, JavaModuleType.getModuleType());
            for (Module module : modulesOfType) {
                List<VirtualFile> configurationFiles = extension.getConfigurationFiles(module, true);
                for (VirtualFile configurationFile : configurationFiles) {
                    readAliasesPackage(pkgSet, configurationFile);
                }
            }
        }

        return pkgSet;
    }

    private void readAliasesPackage(Set<String> pkgSet, VirtualFile configurationFile) {
        FileType fileType = configurationFile.getFileType();
        // yml ,yaml 后缀读取支持
        if (fileType.getDefaultExtension().equals(YML) ||
            fileType.getDefaultExtension().equals(YAML)) {
            // 读取 yml 文件内容
            String content = readContentFromFile(configurationFile);
            try {
                // 首次读取, 可能存在 @变量@ 的场景, 在捕获异常后, 替换掉这种异常字符串, 然后再次读取别名
                readClassesFromYaml(pkgSet, configurationFile.getName(), content);
            } catch (ScannerException e) {
                try {
                    // 通过正则替换掉不符合 yml 格式的字符串, 再次尝试读取别名
                    final String contentReplaced = content.replaceAll("@.*@", "MYBATISX_REPLACE");
                    readClassesFromYaml(pkgSet, configurationFile.getName(), contentReplaced);
                } catch (ScannerException e2) {
                    // 存在 @变量@ 的情况, 暂时忽略这种情况
                    logger.debug("yml parse fail, fileName: {}", configurationFile.getName(), e2);
                }
            }
        }
        // properties 读取支持
        if (fileType.getDefaultExtension().equals(PROPERTIES)) {
            readClassesFromProperties(pkgSet, configurationFile);
        }
    }

    private String readContentFromFile(VirtualFile configurationFile) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (InputStream inputStream = configurationFile.getInputStream()) {
            final int length = inputStream.read(buffer);
            byteArrayOutputStream.write(buffer, 0, length);
        } catch (IOException e) {
            logger.error("read alias exception, fileName: {}", configurationFile.getName(), e);
        }
        return byteArrayOutputStream.toString();
    }

    private void readClassesFromProperties(Set<String> pkgSet, VirtualFile configurationFile) {
        Properties properties = new Properties();
        try (InputStream inputStream = configurationFile.getInputStream()) {
            properties.load(inputStream);
            String typeAliasesPackage = findTypeAliasesPackageByProperties(properties);
            if (typeAliasesPackage != null) {
                if (!StringUtils.isEmpty(typeAliasesPackage)) {
                    pkgSet.add(typeAliasesPackage);
                }
            }
        } catch (IOException e) {
            logger.error("read alias exception, fileName: {}", configurationFile.getName(), e);
        }
    }

    private String findTypeAliasesPackageByProperties(Properties properties) {
        String typeAliasesPackage = properties.getProperty("mybatis.type-aliases-package");
        if (typeAliasesPackage == null) {
            typeAliasesPackage = properties.getProperty("mybatis.typeAliasesPackage");
        }
        if (typeAliasesPackage == null) {
            typeAliasesPackage = properties.getProperty("mybatis-plus.type-aliases-package");
        }
        if (typeAliasesPackage == null) {
            typeAliasesPackage = properties.getProperty("mybatis-plus.typeAliasesPackage");
        }
        return typeAliasesPackage;
    }

    private void readClassesFromYaml(Set<String> classSet, String fileName, String content) {
        Yaml yaml = new Yaml();
        try {
            Iterable<Object> objects = yaml.loadAll(content);
            for (Object object : objects) {
                Object config = findConfig(object);
                Object typeAliasesPackage = findAliasPackage(config);
                if (typeAliasesPackage != null) {
                    final String packageName = typeAliasesPackage.toString();
                    if (!StringUtils.isEmpty(packageName)) {
                        final String[] split = SpringStringUtils.tokenizeToStringArray(packageName, REGEX);
                        classSet.addAll(Arrays.asList(split));
                    }
                }
            }
        } catch (ParserException | ComposerException e) {
            // 错误的 yml 文件, 不予支持
            logger.info("yml parse fail, fileName: {}", fileName, e);
        }
    }

    private Object findAliasPackage(Object config) {
        Object typeAliasesPackage = null;
        if (config != null) {
            if (config instanceof Map) {
                Map mapConfig = (Map) config;
                typeAliasesPackage = mapConfig.get("type-aliases-package");
                if (typeAliasesPackage == null) {
                    typeAliasesPackage = mapConfig.get("typeAliasesPackage");
                }
            }
        }
        return typeAliasesPackage;
    }

    @Nullable
    private Object findConfig(Object object) {
        Object config = null;
        if (object instanceof Map) {
            Map jsonObject = (Map) object;
            config = jsonObject.get("mybatis");
            if (config == null) {
                config = jsonObject.get("mybatis-plus");
            }
            if (config == null) {
                config = jsonObject.get("mybatisPlus");
            }
        }
        return config;
    }

    private static final Logger logger = LoggerFactory.getLogger(SpringBootPackageResolver.class);


}
