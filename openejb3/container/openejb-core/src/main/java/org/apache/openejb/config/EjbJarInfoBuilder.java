/**
 *
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
package org.apache.openejb.config;

import org.apache.openejb.OpenEJBException;
import org.apache.openejb.util.Messages;
import org.apache.openejb.util.Logger;
import org.apache.openejb.jee.oejb3.EjbDeployment;
import org.apache.openejb.jee.oejb3.ResourceLink;
import org.apache.openejb.assembler.classic.*;
import org.apache.openejb.jee.*;
import org.apache.openejb.jee.EntityBean;
import org.apache.openejb.jee.SessionBean;
import org.apache.openejb.loader.SystemInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Iterator;
import java.io.File;

/**
 * @version $Revision$ $Date$
 */
public class EjbJarInfoBuilder {

    public static Messages messages = new Messages("org.apache.openejb.util.resources");
    public static Logger logger = Logger.getInstance("OpenEJB", "org.apache.openejb.util.resources");

    public static final String DEFAULT_SECURITY_ROLE = "openejb.default.security.role";
    private final List<String> deploymentIds = new ArrayList<String>();
    private final List<String> securityRoles = new ArrayList<String>();


    public EjbJarInfo buildInfo(EjbModule jar) throws OpenEJBException {
        deploymentIds.clear();
        securityRoles.clear();
        int beansDeployed = jar.getOpenejbJar().getEjbDeploymentCount();
        int beansInEjbJar = jar.getEjbJar().getEnterpriseBeans().length;

        if (beansInEjbJar != beansDeployed) {
            Map<String, EjbDeployment> deployed = jar.getOpenejbJar().getDeploymentsByEjbName();
            for (EnterpriseBean bean : jar.getEjbJar().getEnterpriseBeans()) {
                if (!deployed.containsKey(bean.getEjbName())){
                    ConfigUtils.logger.i18n.warning("conf.0018", bean.getEjbName(), jar.getJarURI());
                }
            }
            String message = messages.format("conf.0008", jar.getJarURI(), "" + beansInEjbJar, "" + beansDeployed);
            logger.warning(message);
            throw new OpenEJBException(message);
        }

        Map<String, EjbDeployment> ejbds = jar.getOpenejbJar().getDeploymentsByEjbName();
        Map<String, EnterpriseBeanInfo> infos = new HashMap<String, EnterpriseBeanInfo>();
        Map<String, EnterpriseBean> items = new HashMap<String, EnterpriseBean>();

        EjbJarInfo ejbJar = new EjbJarInfo();
        ejbJar.jarPath = jar.getJarURI();
        ejbJar.moduleId = new File(ejbJar.jarPath).getName().replaceFirst(".jar$","");


        for (EnterpriseBean bean : jar.getEjbJar().getEnterpriseBeans()) {
            EnterpriseBeanInfo beanInfo;
            if (bean instanceof org.apache.openejb.jee.SessionBean) {
                beanInfo = initSessionBean((SessionBean) bean, ejbds);
            } else if (bean instanceof org.apache.openejb.jee.EntityBean) {
                beanInfo = initEntityBean((EntityBean) bean, ejbds);
            } else if (bean instanceof org.apache.openejb.jee.MessageDrivenBean) {
                beanInfo = initMessageBean((MessageDrivenBean) bean, ejbds);
            } else {
                throw new OpenEJBException("Unknown bean type: "+bean.getClass().getName());
            }
            ejbJar.enterpriseBeans.add(beanInfo);

            if (deploymentIds.contains(beanInfo.ejbDeploymentId)) {
                String message = messages.format("conf.0100", beanInfo.ejbDeploymentId, jar.getJarURI(), beanInfo.ejbName);
                logger.warning(message);
                throw new OpenEJBException(message);
            }

            deploymentIds.add(beanInfo.ejbDeploymentId);

            beanInfo.codebase = jar.getJarURI();
            infos.put(beanInfo.ejbName, beanInfo);
            items.put(beanInfo.ejbName, bean);

            if (bean.getSecurityIdentity() != null) {
                beanInfo.runAs = bean.getSecurityIdentity().getRunAs();
            }

        }

        initJndiReferences(ejbds, infos, items);

        if (jar.getEjbJar().getAssemblyDescriptor() != null) {
            initInterceptors(jar, ejbJar, infos);
            initSecurityRoles(jar, ejbJar);
            initMethodPermissions(jar, ejbds, ejbJar);
            initMethodTransactions(jar, ejbds, ejbJar);

            for (EnterpriseBeanInfo bean : ejbJar.enterpriseBeans) {
                resolveRoleLinks(jar, bean, items.get(bean.ejbName));
            }
        }

        if (jar.getEjbJar().getRelationships() != null) {
            initRelationships(jar, infos);
        }

        if (!"tomcat-webapp".equals(SystemInstance.get().getProperty("openejb.loader"))) {
//            try {
//                File jarFile = new File(jar.getJarURI());
//
//                SystemInstance.get().getClassPath().addJarToPath(jarFile.toURL());
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
        }
        return ejbJar;
    }

    private void initRelationships(EjbModule jar, Map<String, EnterpriseBeanInfo> infos) throws OpenEJBException {
        for (EjbRelation ejbRelation : jar.getEjbJar().getRelationships().getEjbRelation()) {
            Iterator<EjbRelationshipRole> iterator = ejbRelation.getEjbRelationshipRole().iterator();
            EjbRelationshipRole left = iterator.next();
            EjbRelationshipRole right = iterator.next();

            // left role info
            CmrFieldInfo leftCmrFieldInfo = initRelationshipRole(left, infos);
            CmrFieldInfo rightCmrFieldInfo = initRelationshipRole(right, infos);
            leftCmrFieldInfo.mappedBy = rightCmrFieldInfo;
            rightCmrFieldInfo.mappedBy = leftCmrFieldInfo;
        }
    }

    private CmrFieldInfo initRelationshipRole(EjbRelationshipRole role, Map<String, EnterpriseBeanInfo> infos) throws OpenEJBException {
        CmrFieldInfo cmrFieldInfo = new CmrFieldInfo();

        // find the entityBeanInfo info for this role
        String ejbName = role.getRelationshipRoleSource().getEjbName();
        EnterpriseBeanInfo enterpriseBeanInfo = infos.get(ejbName);
        if (enterpriseBeanInfo == null) {
            throw new OpenEJBException("Relation role source ejb not found " + ejbName);
        }
        if (!(enterpriseBeanInfo instanceof EntityBeanInfo)) {
            throw new OpenEJBException("Relation role source ejb is not an entity bean " + ejbName);
        }
        EntityBeanInfo entityBeanInfo = (EntityBeanInfo) enterpriseBeanInfo;
        cmrFieldInfo.roleSource = entityBeanInfo;

        // RoleName: this may be null
        cmrFieldInfo.roleName = role.getEjbRelationshipRoleName();

        // CmrFieldName: is null for uni-directional relationships
        if (role.getCmrField() != null) {
            cmrFieldInfo.fieldName = role.getCmrField().getCmrFieldName();
            // CollectionType: java.util.Collection or java.util.Set
            if (role.getCmrField().getCmrFieldType() != null) {
                cmrFieldInfo.fieldType = role.getCmrField().getCmrFieldType().toString();
            }
        }

        // CascadeDelete
        cmrFieldInfo.cascadeDelete = role.getCascadeDelete();
        // Multiplicity: one or many
        cmrFieldInfo.many = role.getMultiplicity() == Multiplicity.MANY;

        // add the field to the entityBean
        entityBeanInfo.cmrFields.add(cmrFieldInfo);

        return cmrFieldInfo;
    }

    private void initInterceptors(EjbModule jar, EjbJarInfo ejbJar, Map<String, EnterpriseBeanInfo> beanInfos) throws OpenEJBException {
        if (jar.getEjbJar().getInterceptors() == null) return;
        if (jar.getEjbJar().getAssemblyDescriptor() == null) return;
        if (jar.getEjbJar().getAssemblyDescriptor().getInterceptorBinding() == null) return;

        for (Interceptor s : jar.getEjbJar().getInterceptors()) {
            InterceptorInfo info = new InterceptorInfo();

            info.clazz = s.getInterceptorClass();

            copyCallbacks(s.getAroundInvoke(), info.aroundInvoke);

            copyCallbacks(s.getPostConstruct(), info.postConstruct);
            copyCallbacks(s.getPreDestroy(), info.preDestroy);

            copyCallbacks(s.getPostActivate(), info.postActivate);
            copyCallbacks(s.getPrePassivate(), info.prePassivate);

            ejbJar.interceptors.add(info);
        }

        for (InterceptorBinding binding : jar.getEjbJar().getAssemblyDescriptor().getInterceptorBinding()) {
            InterceptorBindingInfo info = new InterceptorBindingInfo();
            info.ejbName = binding.getEjbName();
            info.excludeClassInterceptors = binding.getExcludeClassInterceptors();
            info.excludeDefaultInterceptors = binding.getExcludeDefaultInterceptors();
            info.interceptors.addAll(binding.getInterceptorClass());
            if (binding.getInterceptorOrder() != null) {
                info.interceptorOrder.addAll(binding.getInterceptorOrder().getInterceptorClass());
            }

            info.method = toInfo(binding.getMethod());
            ejbJar.interceptorBindings.add(info);
        }
    }

    private void initJndiReferences(Map<String, EjbDeployment> ejbds, Map<String, EnterpriseBeanInfo> beanInfos, Map<String, EnterpriseBean> beanData) throws OpenEJBException {

        JndiEncInfoBuilder jndiEncInfoBuilder = new JndiEncInfoBuilder(beanInfos.values(), null);

        for (EnterpriseBeanInfo beanInfo : beanInfos.values()) {

            String ejbName = beanInfo.ejbName;
            JndiConsumer jndiConsumer = beanData.get(ejbName);

            EjbDeployment ejbDeployment = ejbds.get(ejbName);

            // Link all the resource refs
            List<ResourceRef> resourceRefs = jndiConsumer.getResourceRef();
            for (ResourceRef res : resourceRefs) {
                ResourceLink resourceLink = ejbDeployment.getResourceLink(res.getResRefName());
                if (resourceLink != null && resourceLink.getResId() != null /* don't overwrite with null */) {
                    res.setResLink(resourceLink.getResId());
                }
            }

            JndiEncInfo jndi = jndiEncInfoBuilder.build(jndiConsumer, ejbName);

            beanInfo.jndiEnc = jndi;
        }
    }

    private void initMethodTransactions(EjbModule jar, Map ejbds, EjbJarInfo ejbJarInfo) {

        List<ContainerTransaction> containerTransactions = jar.getEjbJar().getAssemblyDescriptor().getContainerTransaction();
        for (ContainerTransaction cTx : containerTransactions) {
            MethodTransactionInfo info = new MethodTransactionInfo();

            info.description = cTx.getDescription();
            info.transAttribute = cTx.getTransAttribute().toString();
            info.methods.addAll(getMethodInfos(cTx.getMethod(), ejbds));
            ejbJarInfo.methodTransactions.add(info);
        }
    }

    private void initSecurityRoles(EjbModule jar, EjbJarInfo ejbJarInfo) {

        List<SecurityRole> roles = jar.getEjbJar().getAssemblyDescriptor().getSecurityRole();

        for (SecurityRole sr : roles) {
            SecurityRoleInfo info = new SecurityRoleInfo();

            info.description = sr.getDescription();
            info.roleName = sr.getRoleName();

            if (securityRoles.contains(sr.getRoleName())) {
                ConfigUtils.logger.i18n.warning("conf.0102", jar.getJarURI(), sr.getRoleName());
            } else {
                securityRoles.add(sr.getRoleName());
            }
            ejbJarInfo.securityRoles.add(info);
        }
    }

    private void initMethodPermissions(EjbModule jar, Map ejbds, EjbJarInfo ejbJarInfo) {

        List<MethodPermission> methodPermissions = jar.getEjbJar().getAssemblyDescriptor().getMethodPermission();

        for (MethodPermission mp : methodPermissions) {
            MethodPermissionInfo info = new MethodPermissionInfo();

            info.description = mp.getDescription();
            info.roleNames.addAll(mp.getRoleName());
            info.methods.addAll(getMethodInfos(mp.getMethod(), ejbds));
            info.unchecked = mp.getUnchecked();

            ejbJarInfo.methodPermissions.add(info);
        }
    }

    private void resolveRoleLinks(EjbModule jar, EnterpriseBeanInfo bean, JndiConsumer item) {
        if (!(item instanceof RemoteBean)) {
            return;
        }

        RemoteBean rb = (RemoteBean) item;

        List<SecurityRoleRef> refs = rb.getSecurityRoleRef();
        for (SecurityRoleRef ref : refs) {
            SecurityRoleReferenceInfo info = new SecurityRoleReferenceInfo();

            info.description = ref.getDescription();
            info.roleLink = ref.getRoleLink();
            info.roleName = ref.getRoleName();

            if (info.roleLink == null) {
                ConfigUtils.logger.i18n.warning("conf.0009", info.roleName, bean.ejbName, jar.getJarURI());
                info.roleLink = DEFAULT_SECURITY_ROLE;
            }
            bean.securityRoleReferences.add(info);
        }
    }

    private List<MethodInfo> getMethodInfos(List<Method> ms, Map ejbds) {
        if (ms == null) return Collections.emptyList();

        List<MethodInfo> mi = new ArrayList<MethodInfo>(ms.size());
        for (Method method : ms) {
            MethodInfo methodInfo = new MethodInfo();

            EjbDeployment d = (EjbDeployment) ejbds.get(method.getEjbName());

            methodInfo.description = method.getDescription();
            methodInfo.ejbDeploymentId = d.getDeploymentId();
            methodInfo.ejbName = method.getEjbName();
            methodInfo.methodIntf = (method.getMethodIntf() == null) ? null : method.getMethodIntf().toString();
            methodInfo.methodName = method.getMethodName();

            MethodParams mp = method.getMethodParams();
            if (mp != null) {
                methodInfo.methodParams = mp.getMethodParam();
            }
            mi.add(methodInfo);
        }

        return mi;
    }

    private EnterpriseBeanInfo initSessionBean(SessionBean s, Map m) throws OpenEJBException {
        EnterpriseBeanInfo bean = null;

        if (s.getSessionType() == SessionType.STATEFUL) {
            bean = new StatefulBeanInfo();
            StatefulBeanInfo stateful = ((StatefulBeanInfo) bean);

            copyCallbacks(s.getPostActivate(), stateful.postActivate);
            copyCallbacks(s.getPrePassivate(), stateful.prePassivate);

            for (InitMethod initMethod : s.getInitMethod()) {
                InitMethodInfo init = new InitMethodInfo();
                init.beanMethod = toInfo(initMethod.getBeanMethod());
                init.createMethod = toInfo(initMethod.getCreateMethod());
                stateful.initMethods.add(init);
            }

            for (RemoveMethod removeMethod : s.getRemoveMethod()) {
                RemoveMethodInfo remove = new RemoveMethodInfo();
                remove.beanMethod = toInfo(removeMethod.getBeanMethod());
                remove.retainIfException = removeMethod.getRetainIfException();
                stateful.removeMethods.add(remove);
            }

        } else {
            bean = new StatelessBeanInfo();
        }

        bean.timeoutMethod = toInfo(s.getTimeoutMethod());

        copyCallbacks(s.getAroundInvoke(), bean.aroundInvoke);
        copyCallbacks(s.getPostConstruct(), bean.postConstruct);
        copyCallbacks(s.getPreDestroy(), bean.preDestroy);

        EjbDeployment d = (EjbDeployment) m.get(s.getEjbName());
        if (d == null) {
            throw new OpenEJBException("No deployment information in openejb-jar.xml for bean "
                    + s.getEjbName()
                    + ". Please redeploy the jar");
        }
        bean.ejbDeploymentId = d.getDeploymentId();
        bean.containerId = d.getContainerId();

        Icon icon = s.getIcon();
        bean.largeIcon = (icon == null) ? null : icon.getLargeIcon();
        bean.smallIcon = (icon == null) ? null : icon.getSmallIcon();
        bean.description = s.getDescription();
        bean.displayName = s.getDisplayName();
        bean.ejbClass = s.getEjbClass();
        bean.ejbName = s.getEjbName();
        bean.home = s.getHome();
        bean.remote = s.getRemote();
        bean.localHome = s.getLocalHome();
        bean.local = s.getLocal();
        bean.businessLocal = s.getBusinessLocal();
        bean.businessRemote = s.getBusinessRemote();
        TransactionType txType = s.getTransactionType();
        bean.transactionType = (txType != null)?txType.toString(): TransactionType.CONTAINER.toString();

        return bean;
    }

    private EnterpriseBeanInfo initMessageBean(MessageDrivenBean mdb, Map m) throws OpenEJBException {
        MessageDrivenBeanInfo bean = new MessageDrivenBeanInfo();

        bean.timeoutMethod = toInfo(mdb.getTimeoutMethod());

        copyCallbacks(mdb.getAroundInvoke(), bean.aroundInvoke);
        copyCallbacks(mdb.getPostConstruct(), bean.postConstruct);
        copyCallbacks(mdb.getPreDestroy(), bean.preDestroy);

        EjbDeployment d = (EjbDeployment) m.get(mdb.getEjbName());
        if (d == null) {
            throw new OpenEJBException("No deployment information in openejb-jar.xml for bean "
                    + mdb.getEjbName()
                    + ". Please redeploy the jar");
        }
        bean.ejbDeploymentId = d.getDeploymentId();
        bean.containerId = d.getContainerId();

        Icon icon = mdb.getIcon();
        bean.largeIcon = (icon == null) ? null : icon.getLargeIcon();
        bean.smallIcon = (icon == null) ? null : icon.getSmallIcon();
        bean.description = mdb.getDescription();
        bean.displayName = mdb.getDisplayName();
        bean.ejbClass = mdb.getEjbClass();
        bean.ejbName = mdb.getEjbName();
        TransactionType txType = mdb.getTransactionType();
        bean.transactionType = (txType != null)?txType.toString(): TransactionType.CONTAINER.toString();

        if (mdb.getMessagingType() != null) {
            bean.mdbInterface = mdb.getMessagingType();
        } else {
            bean.mdbInterface = "javax.jms.MessageListener";
        }

        if (mdb.getMessageDestinationType() != null) {
            bean.activationProperties.put("destinationType", mdb.getMessageDestinationType());
        }
        ActivationConfig activationConfig = mdb.getActivationConfig();
        if (activationConfig != null) {
            for (ActivationConfigProperty property : activationConfig.getActivationConfigProperty()) {
                String name = property.getActivationConfigPropertyName();
                String value = property.getActivationConfigPropertyValue();
                bean.activationProperties.put(name, value);
            }
        }

        return bean;
    }

    private NamedMethodInfo toInfo(NamedMethod method) {
        if (method == null) return null;

        NamedMethodInfo info = new NamedMethodInfo();

        info.methodName = method.getMethodName();

        if (method.getMethodParams() != null) {
            info.methodParams = method.getMethodParams().getMethodParam();
        }

        return info;
    }

    private void copyCallbacks(List<? extends CallbackMethod> from, List<CallbackInfo> to) {
        for (CallbackMethod callback : from) {
            CallbackInfo info = new CallbackInfo();
            info.className = callback.getClassName();
            info.method = callback.getMethodName();
            to.add(info);
        }
    }

    private EnterpriseBeanInfo initEntityBean(EntityBean e, Map m) throws OpenEJBException {
        EntityBeanInfo bean = new EntityBeanInfo();

        EjbDeployment d = (EjbDeployment) m.get(e.getEjbName());
        if (d == null) {
            throw new OpenEJBException("No deployment information in openejb-jar.xml for bean "
                    + e.getEjbName()
                    + ". Please redeploy the jar");
        }
        bean.ejbDeploymentId = d.getDeploymentId();
        bean.containerId = d.getContainerId();

        Icon icon = e.getIcon();
        bean.largeIcon = (icon == null) ? null : icon.getLargeIcon();
        bean.smallIcon = (icon == null) ? null : icon.getSmallIcon();
        bean.description = e.getDescription();
        bean.displayName = e.getDisplayName();
        bean.ejbClass = e.getEjbClass();
        bean.abstractSchemaName = e.getAbstractSchemaName();
        bean.ejbName = e.getEjbName();
        bean.home = e.getHome();
        bean.remote = e.getRemote();
        bean.localHome = e.getLocalHome();
        bean.local = e.getLocal();
        bean.transactionType = "Container";

        bean.primKeyClass = e.getPrimKeyClass();
        bean.primKeyField = e.getPrimkeyField();
        bean.persistenceType = e.getPersistenceType().toString();
        bean.reentrant = e.getReentrant() + "";

        CmpVersion cmpVersion = e.getCmpVersion();
        if (e.getPersistenceType() == PersistenceType.CONTAINER) {
            if (cmpVersion != null && cmpVersion == CmpVersion.CMP1){
                bean.cmpVersion = 1;
            } else {
                bean.cmpVersion = 2;
            }
        }

        List<CmpField> cmpFields = e.getCmpField();
        for (CmpField cmpField : cmpFields) {
            bean.cmpFieldNames.add(cmpField.getFieldName());
        }

        if (bean.persistenceType.equalsIgnoreCase("Container")) {
            for (Query q : e.getQuery()) {
                QueryInfo query = new QueryInfo();
                query.queryStatement = q.getEjbQl().trim();

                MethodInfo method = new MethodInfo();
                QueryMethod qm = q.getQueryMethod();
                method.methodName = qm.getMethodName();
                method.methodParams = qm.getMethodParams().getMethodParam();
                query.method = method;
                bean.queries.add(query);
            }

            for (org.apache.openejb.jee.oejb3.Query q : d.getQuery()) {
                QueryInfo query = new QueryInfo();
                query.description = q.getDescription();
                query.queryStatement = q.getObjectQl().trim();

                MethodInfo method = new MethodInfo();
                org.apache.openejb.jee.oejb3.QueryMethod qm = q.getQueryMethod();
                method.methodName = qm.getMethodName();
                method.methodParams = qm.getMethodParams().getMethodParam();
                query.method = method;
                bean.queries.add(query);
            }
        }
        return bean;
    }
}
