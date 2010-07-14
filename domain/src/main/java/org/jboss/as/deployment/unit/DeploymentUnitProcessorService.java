/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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

package org.jboss.as.deployment.unit;

import org.jboss.msc.service.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.Value;
import org.jboss.msc.value.Values;

/**
 * Service responsible for wrapping a deployment unit processor to support life-cycle management and injection.
 *
 * @author John E. Bailey
 */
public class DeploymentUnitProcessorService<T extends DeploymentUnitProcessor> implements Service<T> {
    private final Value<T> deploymentProcessorValue;

    public DeploymentUnitProcessorService(final Value<T> deploymentProcessorValue) {
        this.deploymentProcessorValue = deploymentProcessorValue;
    }

    public DeploymentUnitProcessorService(final T deploymentProcessor) {
        this(Values.immediateValue(deploymentProcessor));
    }

    @Override
    public void start(final StartContext context) throws StartException {
    }

    @Override
    public void stop(final StopContext context) {
    }

    @Override
    public T getValue() throws IllegalStateException {
        return deploymentProcessorValue.getValue();
    }
}