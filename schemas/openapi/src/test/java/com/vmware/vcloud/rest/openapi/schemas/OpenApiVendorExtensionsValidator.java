/* *****************************************************************************
 * api-extension-template-vcloud-director
 * Copyright 2019-2021 VMware, Inc.  All rights reserved. *
 * SPDX-License-Identifier: BSD-2-Clause
 * *****************************************************************************/

package com.vmware.vcloud.rest.openapi.schemas;

/*-
 * #%L
 * vcd-openapi-schemas :: vCloud Director OpenAPI Definitions
 * %%
 * Copyright (C) 2018 - 2021 VMware
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import com.fasterxml.jackson.databind.JsonNode;

import org.testng.Assert;
import org.testng.annotations.Test;

import io.swagger.util.Yaml;


/**
 * Insert your comment for OpenApiVendorExtensionsValidator here
 *
 */
public class OpenApiVendorExtensionsValidator {
    private static final StringWriter MESSAGES = new StringWriter();
    private static final BufferedWriter OUT = new BufferedWriter(MESSAGES);

    private static final Path SCHEMAS = Path.of("schemas");

    private static final class SwaggerObject {
        final String filename;
        final JsonNode node;

        SwaggerObject(String filename, JsonNode swaggerObject) {
            this.filename = filename;
            this.node = swaggerObject;
        }
    }

    private final Queue<SwaggerObject> targets = new LinkedList<>();

    private final List<ValidationVisitor> validators = Arrays.asList(
            new SwaggerObjectIntrospector(),
            new VersioningExtensionsValidator()
            );

    public OpenApiVendorExtensionsValidator() throws IOException, URISyntaxException {
        System.out.println("Initializing constructor");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL url = loader.getResource(SCHEMAS.toString());

        System.out.println(url.toURI().toASCIIString());
        Files.find(Paths.get(url.toURI()), Integer.MAX_VALUE, (path, dontcare) -> path.toString().endsWith("yaml")).forEach(this::loadSchemaFile);
    }

    @Test(groups = "Minimum")
    public void verifyExtensions() {
        SwaggerObject target;
        while ((target = targets.poll()) != null) {
            for (ValidationVisitor validator : validators) {
                validator.validate(target);
            }
        }

        try {
            OUT.flush();
            OUT.close();
        } catch (IOException e) {
         // highly unlikely to get an IOException writing a StringWriter
        }
        String errorMessages = MESSAGES.toString();
        if (errorMessages == null || errorMessages.isBlank()) {
            return;
        }

        Assert.fail(errorMessages);
    }

    private void loadSchemaFile(Path path) {
        System.out.println("reading: " + path);

        JsonNode schema;
        try {
            InputStream inputStream = new FileInputStream(new File(path.toUri()));
            schema = Yaml.mapper().readTree(inputStream);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing schema file " + path.toAbsolutePath().toString(), e);
        }

        targets.add(new SwaggerObject(path.toString(), schema));
    }

    interface ValidationVisitor {
        boolean validate(SwaggerObject swaggerObject);
    }

    class SwaggerObjectIntrospector implements ValidationVisitor {
        @Override
        public boolean validate(SwaggerObject swaggerObject) {
            final JsonNode jsonNode = swaggerObject.node;
            for (JsonNode child : jsonNode) {
                if (child.isContainerNode()) {
                    targets.add(new SwaggerObject(swaggerObject.filename, child));
                }
            }
            return true;
        }
    }

    class VersioningExtensionsValidator implements ValidationVisitor {

        @Override
        public boolean validate(SwaggerObject swaggerObject) {
            JsonNode theNode = swaggerObject.node;
            if (!theNode.isContainerNode()) {
                return true;
            }

            evaluateVersionExtension(swaggerObject, theNode, "x-vcloud-added-in");
            evaluateVersionExtension(swaggerObject, theNode, "x-vcloud-deprecated-in");
            evaluateVersionExtension(swaggerObject, theNode, "x-vcloud-removed-in");

            return true;
        }

        private void evaluateVersionExtension(SwaggerObject swaggerObject, JsonNode theNode, String versionExtensionName) {
            if (!theNode.has(versionExtensionName)) {
                return;
            }

            final JsonNode xVloudAddedInNode = theNode.get(versionExtensionName);
            if (xVloudAddedInNode.isTextual()) {
                return;
            }

            try {
                OUT.write(String.format("[%s] detected %s: %s to be of type %s. Th version must be enclosed in double-quotes to be properly parsed as a String",
                        swaggerObject.filename, versionExtensionName, xVloudAddedInNode.asText(), xVloudAddedInNode.getNodeType()));
                OUT.newLine();
            } catch (IOException e) {
                // again ... highly unlikely to get an IOException writing a StringWriter
            }
        }
    }
}
