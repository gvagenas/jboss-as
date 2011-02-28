/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.weld.deployment.processors;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.annotation.AnnotationIndexUtils;
import org.jboss.as.server.deployment.module.ResourceRoot;
import org.jboss.as.weld.WeldDeploymentMarker;
import org.jboss.as.weld.deployment.BeanArchiveMetadata;
import org.jboss.as.weld.deployment.BeanDeploymentArchiveImpl;
import org.jboss.as.weld.deployment.BeanDeploymentModule;
import org.jboss.as.weld.deployment.WeldAttachments;
import org.jboss.as.weld.deployment.WeldDeploymentMetadata;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.Index;
import org.jboss.logging.Logger;
import org.jboss.modules.Module;
import org.jboss.weld.bootstrap.spi.BeansXml;

/**
 * Deployment processor that builds bean archives and attaches them to the deployment
 *<p>
 * Currently this is done by pulling the information out of the jandex {@link Index}.
 * <p>
 *
 * @author Stuart Douglas
 *
 */
public class BeanArchiveProcessor implements DeploymentUnitProcessor {

    private static final Logger log = Logger.getLogger("org.jboss.weld");

    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final WeldDeploymentMetadata cdiDeploymentMetadata = deploymentUnit
                .getAttachment(WeldDeploymentMetadata.ATTACHMENT_KEY);

        if (!WeldDeploymentMarker.isWeldDeployment(deploymentUnit)) {
            return;
        }

        final String beanArchiveIdPrefix;
        if (deploymentUnit.getParent() == null) {
            beanArchiveIdPrefix = deploymentUnit.getName();
        } else {
            beanArchiveIdPrefix = deploymentUnit.getParent().getName() + "." + deploymentUnit.getName();
        }

        final Set<BeanDeploymentArchiveImpl> beanDeploymentArchives = new HashSet<BeanDeploymentArchiveImpl>();
        log.info("Processing CDI deployment: " + phaseContext.getDeploymentUnit().getName());

        final Map<ResourceRoot, Index> indexes = AnnotationIndexUtils.getAnnotationIndexes(deploymentUnit);

        final Module module = phaseContext.getDeploymentUnit().getAttachment(Attachments.MODULE);
        boolean rootArchiveFound = false;
        if (cdiDeploymentMetadata != null) {
            // this can be null for ear deployments
            // however we still want to create a module level bean manager
            for (BeanArchiveMetadata beanArchiveMetadata : cdiDeploymentMetadata.getBeanArchiveMetadata()) {
                BeanDeploymentArchiveImpl bda = createBeanDeploymentArchive(indexes.get(beanArchiveMetadata.getResourceRoot()),
                        beanArchiveMetadata, module, beanArchiveIdPrefix);
                beanDeploymentArchives.add(bda);
                if (beanArchiveMetadata.isDeploymentRoot()) {
                    rootArchiveFound = true;
                    deploymentUnit.putAttachment(WeldAttachments.DEPLOYMENT_ROOT_BEAN_DEPLOYMENT_ARCHIVE, bda);
                }
            }
        }
        if (!rootArchiveFound) {
            BeanDeploymentArchiveImpl bda = new BeanDeploymentArchiveImpl(Collections.<String> emptySet(),
                    BeansXml.EMPTY_BEANS_XML, module, beanArchiveIdPrefix);
            beanDeploymentArchives.add(bda);
            deploymentUnit.putAttachment(WeldAttachments.DEPLOYMENT_ROOT_BEAN_DEPLOYMENT_ARCHIVE, bda);
        }

        deploymentUnit.putAttachment(WeldAttachments.BEAN_DEPLOYMENT_MODULE, new BeanDeploymentModule(beanDeploymentArchives));
    }

    private BeanDeploymentArchiveImpl createBeanDeploymentArchive(final Index index, BeanArchiveMetadata beanArchiveMetadata,
            Module module, String beanArchivePrefix) throws DeploymentUnitProcessingException {

        Set<String> classNames = new HashSet<String>();
        // index may be null if a war has a beans.xml but no WEB-INF/classes
        if (index != null) {
            for (ClassInfo classInfo : index.getKnownClasses()) {
                classNames.add(classInfo.name().toString());
            }
        }
        return new BeanDeploymentArchiveImpl(classNames, beanArchiveMetadata.getBeansXml(), module, beanArchivePrefix
                + beanArchiveMetadata.getResourceRoot().getRoot().getPathName());
    }

    @Override
    public void undeploy(DeploymentUnit context) {

    }

}
