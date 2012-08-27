/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.config.typed;

import org.apache.openejb.config.sys.Container;
import org.apache.openejb.config.typed.util.Builders;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.Properties;

@XmlRootElement(name = "CmpEntityContainer")
public class CmpEntityContainerBuilder extends Container {

    private String cmpEngineFactory = "org.apache.openejb.core.cmp.jpa.JpaCmpEngineFactory";

    public CmpEntityContainerBuilder() {
        setClassName("org.apache.openejb.core.cmp.CmpContainer");
        setType("CMP_ENTITY");
        setId("CmpEntityContainer");

        setConstructor("id, transactionManager, securityService, CmpEngineFactory");

    }

    public CmpEntityContainerBuilder id(String id) {
        setId(id);
        return this;
    }

    public CmpEntityContainerBuilder withCmpEngineFactory(String cmpEngineFactory) {
        this.cmpEngineFactory = cmpEngineFactory;
        return this;
    }

    public void setCmpEngineFactory(String cmpEngineFactory) {
        this.cmpEngineFactory = cmpEngineFactory;
    }

    @XmlAttribute
    public String getCmpEngineFactory() {
        return cmpEngineFactory;
    }

    public Properties getProperties() {
        return Builders.getProperties(this);
    }

}