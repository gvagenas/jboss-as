/*
* JBoss, Home of Professional Open Source.
* Copyright 2006, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.as.server.mgmt;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.remote.ModelControllerOperationHandlerService;
import org.jboss.as.protocol.MessageHandler;
import org.jboss.as.server.Services;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.value.InjectedValue;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @version $Revision: 1.1 $
 */
public class ServerControllerOperationHandlerService extends ModelControllerOperationHandlerService {

    public static final ServiceName SERVICE_NAME = Services.JBOSS_SERVER_CONTROLLER.append(ModelControllerOperationHandlerService.OPERATION_HANDLER_NAME_SUFFIX);

    private InjectedValue<ManagementCommunicationService> managementCommunicationService = new InjectedValue<ManagementCommunicationService>();

    public ServerControllerOperationHandlerService() {
        super(ModelControllerClient.Type.STANDALONE);
    }

    @Override
    public void start(StartContext context) throws StartException {
        super.start(context);
        managementCommunicationService.getValue().addHandler(getValue());
    }

    public void stop(StartContext context) {
        managementCommunicationService.getValue().removeHandler(getValue());
    }

    public InjectedValue<ManagementCommunicationService> getManagementCommunicationServiceValue() {
        return managementCommunicationService;
    }

    @Override
    protected MessageHandler getInitialMessageHandler() {
        return managementCommunicationService.getValue().getInitialMessageHandler();
    }
}
