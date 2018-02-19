package io.swagger.codegen.languages;

import io.swagger.codegen.*;
import io.swagger.models.ModelImpl;
import io.swagger.models.properties.*;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class LightningClientCodegen extends AbstractTypeScriptClientCodegen {
    private static final SimpleDateFormat SNAPSHOT_SUFFIX_FORMAT = new SimpleDateFormat("yyyyMMddHHmm");

    public static final String CLIENT_NAME = "clientName";

    public LightningClientCodegen() {
        super();
        this.outputFolder = "generated-code/typescript-angular";

        embeddedTemplateDir = templateDir = "lightning-client";
        modelTemplateFiles.put("model.model.mustache", ".ts");
        apiTemplateFiles.put("api.service.mustache", ".ts");
        languageSpecificPrimitives.add("Blob");
        typeMapping.put("file", "Blob");
        apiPackage = "api";
        modelPackage = "model";

        this.cliOptions.add(new CliOption(CLIENT_NAME, "The name for the generated client, e.g. Lightning will generate LightningClient"));
    }

    @Override
    protected void addAdditionPropertiesToCodeGenModel(CodegenModel codegenModel, ModelImpl swaggerModel) {
        codegenModel.additionalPropertiesType = getTypeDeclaration(swaggerModel.getAdditionalProperties());
        addImport(codegenModel, codegenModel.additionalPropertiesType);
    }

    @Override
    public String getName() { return "lightning-client"; }

    @Override
    public String getHelp() { return "Generate an Angular client for Lightning AMS"; }

    @Override
    public void processOpts() {
        super.processOpts();
        supportingFiles.add(new SupportingFile("models.mustache", modelPackage().replace('.', File.separatorChar), "index.ts"));
        supportingFiles.add(new SupportingFile("apis.mustache", apiPackage().replace('.', File.separatorChar), "index.ts"));
        supportingFiles.add(new SupportingFile("index.mustache", getIndexDirectory(), "index.ts"));
        supportingFiles.add(new SupportingFile(
            "lightning-client.module.mustache",
            getIndexDirectory(),
            additionalProperties.get(CLIENT_NAME).toString().toLowerCase() + "-client.module.ts"
        ));
        supportingFiles.add(new SupportingFile(
            "lightning-client.config.mustache",
            getIndexDirectory(),
            additionalProperties.get(CLIENT_NAME).toString().toLowerCase() + "-client.config.ts"
        ));
        supportingFiles.add(new SupportingFile("variables.mustache", getIndexDirectory(), "variables.ts"));
        supportingFiles.add(new SupportingFile("util.mustache", getIndexDirectory(), "util.ts"));
        supportingFiles.add(new SupportingFile("gitignore", "", ".gitignore"));

        // NPM files
        supportingFiles.add(new SupportingFile("README.mustache", getIndexDirectory(), "README.md"));
        supportingFiles.add(new SupportingFile("package.mustache", getIndexDirectory(), "package.json"));
        supportingFiles.add(new SupportingFile("typings.mustache", getIndexDirectory(), "typings.json"));
        supportingFiles.add(new SupportingFile("tsconfig.mustache", getIndexDirectory(), "tsconfig.json"));

        additionalProperties.put("clientNameLower", additionalProperties.get(CLIENT_NAME).toString().toLowerCase());
    }

    private String getIndexDirectory() {
        String indexPackage = modelPackage.substring(0, Math.max(0, modelPackage.lastIndexOf('.')));
        return indexPackage.replace('.', File.separatorChar);
    }

    @Override
    public boolean isDataTypeFile(final String dataType) {
        return dataType != null && dataType.equals("Blob");
    }

    @Override
    public String getTypeDeclaration(Property p) {
        Property inner;
        if (p instanceof ArrayProperty) {
            ArrayProperty mp1 = (ArrayProperty) p;
            inner = mp1.getItems();
            return this.getSwaggerType(p) + "<" + this.getTypeDeclaration(inner) + ">";
        } else if (p instanceof MapProperty) {
            MapProperty mp = (MapProperty) p;
            inner = mp.getAdditionalProperties();
            return "{ [key: string]: " + this.getTypeDeclaration(inner) + "; }";
        } else if (p instanceof FileProperty) {
            return "Blob";
        } else if (p instanceof ObjectProperty) {
            return "any";
        } else {
            return super.getTypeDeclaration(p);
        }
    }

    @Override
    public String getSwaggerType(Property p) {
        String swaggerType = super.getSwaggerType(p);
        if (isLanguagePrimitive(swaggerType) || isLanguageGenericType(swaggerType)) {
            return swaggerType;
        }
        applyLocalTypeMapping(swaggerType);
        return swaggerType;
    }

    private String applyLocalTypeMapping(String type) {
        if (typeMapping.containsKey(type)) {
            type = typeMapping.get(type);
        }
        return type;
    }

    private boolean isLanguagePrimitive(String type) {
        return languageSpecificPrimitives.contains(type);
    }

    private boolean isLanguageGenericType(String type) {
        for (String genericType : languageGenericTypes) {
            if (type.startsWith(genericType + "<")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void postProcessParameter(CodegenParameter parameter) {
        super.postProcessParameter(parameter);
        parameter.dataType = applyLocalTypeMapping(parameter.dataType);
    }

    @Override
    public Map<String, Object> postProcessOperations(Map<String, Object> operations) {
        Map<String, Object> objs = (Map<String, Object>) operations.get("operations");

        // Add filename information for api imports
        objs.put("apiFilename", getApiFilenameFromClassname(objs.get("classname").toString()));

        List<CodegenOperation> ops = (List<CodegenOperation>) objs.get("operation");
        for (CodegenOperation op : ops) {
            op.httpMethod = op.httpMethod.toLowerCase(Locale.ENGLISH);

            // Prep a string buffer where we're going to set up our new version of the string.
            StringBuilder pathBuffer = new StringBuilder();
            StringBuilder parameterName = new StringBuilder();
            int insideCurly = 0;

            // Iterate through existing string, one character at a time.
            for (int i = 0; i < op.path.length(); i++) {
                switch (op.path.charAt(i)) {
                case '{':
                    // We entered curly braces, so track that.
                    insideCurly++;

                    // Add the more complicated component instead of just the brace.
                    pathBuffer.append("${encodeURIComponent(String(");
                    break;
                case '}':
                    // We exited curly braces, so track that.
                    insideCurly--;

                    // Add the more complicated component instead of just the brace.
                    pathBuffer.append(toVarName(parameterName.toString()));
                    pathBuffer.append("))}");
                    parameterName.setLength(0);
                    break;
                default:
                    if (insideCurly > 0) {
                        parameterName.append(op.path.charAt(i));
                    } else {
                        pathBuffer.append(op.path.charAt(i));
                    }
                    break;
                }
            }

            // Overwrite path to TypeScript template string, after applying everything we just did.
            op.path = pathBuffer.toString();
        }

        // Add additional filename information for model imports in the services
        List<Map<String, Object>> imports = (List<Map<String, Object>>) operations.get("imports");
        for (Map<String, Object> im : imports) {
            im.put("filename", im.get("import"));
            im.put("classname", getModelnameFromModelFilename(im.get("filename").toString()));
        }

        return operations;
    }

    @Override
    public Map<String, Object> postProcessModels(Map<String, Object> objs) {
        Map<String, Object> result = super.postProcessModels(objs);

        // Add additional filename information for imports
        List<Object> models = (List<Object>) postProcessModelsEnum(result).get("models");
        for (Object _mo : models) {
            Map<String, Object> mo = (Map<String, Object>) _mo;
            CodegenModel cm = (CodegenModel) mo.get("model");
            mo.put("tsImports", toTsImports(cm, cm.imports));
        }

        return result;
    }

    private List<Map<String, String>> toTsImports(CodegenModel cm, Set<String> imports) {
        List<Map<String, String>> tsImports = new ArrayList<>();
        for (String im : imports) {
            if (!im.equals(cm.classname)) {
                HashMap<String, String> tsImport = new HashMap<>();
                tsImport.put("classname", im);
                tsImport.put("filename", toModelFilename(im));
                tsImports.add(tsImport);
            }
        }
        return tsImports;
    }

    @Override
    public String toApiName(String name) {
        if (name.length() == 0) {
            return "DefaultService";
        }
        return initialCaps(name) + "Service";
    }

    @Override
    public String toApiFilename(String name) {
        if (name.length() == 0) {
            return "default.service";
        }
        return camelize(name, true) + ".service";
    }

    @Override
    public String toEnumName(CodegenProperty property) {
        return StringUtils.capitalize(property.name);
    }

    @Override
    public String toApiImport(String name) {
        return apiPackage() + "/" + toApiFilename(name);
    }

    @Override
    public String toModelFilename(String name) {
        return camelize(toModelName(name), true);
    }

    @Override
    public String toModelImport(String name) {
        return modelPackage() + "/" + toModelFilename(name);
    }

    private String getApiFilenameFromClassname(String classname) {
        String name = classname.substring(0, classname.length() - "Service".length());
        return toApiFilename(name);
    }

    private String getModelnameFromModelFilename(String filename) {
        String name = filename.substring((modelPackage() + "/").length());
        return camelize(name);
    }

}
