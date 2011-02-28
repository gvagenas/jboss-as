/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
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
package org.jboss.as.cli.impl;

import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.OperationRequestBuilder;
import org.jboss.as.cli.OperationRequestParser;



/**
 * Default implementation of CommandParser which expects the following command format:
 *
 * node-type=node-name [, node-type=node-name]* : operation-name ( [name=value [, name=value]*] )
 *
 * the whitespaces are insignificant. E.g.
 *
 * profile=production,subsystem=threads,bounded-queue-thread-pool=pool1:write-core-threads(count=0, per-cpu=20)
 *
 * Each node-type, node-name, operation-name and the argument name as strings are checked to be valid identifiers,
 * i.e. the Character.isJavaIdentifierStart(c) should return true for the first character and the rest should
 * satisfy (Character.isJavaIdentifierPart(c) || c == '-')
 *
 * This implementation is thread-safe. The same instance of this class can be re-used multiple times and
 * can be accessed from multiple threads concurrently w/o synchronization.
 *
 * @author Alexey Loubyansky
 */
public class DefaultOperationRequestParser implements OperationRequestParser {

    public static final String FORMAT = "node-type=node-name [, node-type=node-name]* : operation-name ( [name=value [, name=value]*] )";
    public static final char NODE_SEPARATOR = ',';
    public static final char ADDRESS_OPERATION_SEPARATOR = ':';
    public static final char NODE_TYPE_NAME_SEPARATOR = '=';
    public static final char ARG_LIST_START = '(';
    public static final char ARG_LIST_END = ')';
    public static final char ARG_SEPARATOR = ',';
    public static final char ARG_NAME_VALUE_SEPARATOR = '=';

    public void parse(String cmd, OperationRequestBuilder builder) throws CommandFormatException {

        if(cmd == null) {
            throw new IllegalArgumentException("The command is null!");
        }
        if(builder == null) {
            throw new IllegalArgumentException("The request builder is null!");
        }

        cmd = cmd.trim();

        int addrSepIndex = cmd.indexOf(ADDRESS_OPERATION_SEPARATOR);
        int argListStartIndex = cmd.indexOf(ARG_LIST_START);
        if(argListStartIndex > 0 && addrSepIndex > argListStartIndex) {
            // the symbol belongs to an argument value
            addrSepIndex = - 1;
        }

/*        if(colonIndex < 0) {
            throw new CommandFormatException("Couldn't locate '" + ADDRESS_OPERATION_SEPARATOR + "'. Command '" + cmd + "' doesn't follow the format " + FORMAT);
        }
*/
        if (addrSepIndex > 0) {
            String address = cmd.substring(0, addrSepIndex).trim();
            if (address.isEmpty()) {
                throw new CommandFormatException(
                        "The address part is missing. Command '" + cmd
                                + "' doesn't follow the format " + FORMAT);
            }

            int nodeIndex = 0;
            while (nodeIndex < address.length()) {
                int slashIndex = address.indexOf(NODE_SEPARATOR, nodeIndex);
                if (slashIndex < 0) {
                    slashIndex = address.length();
                }
                String node = address.substring(nodeIndex, slashIndex).trim();
                if (node.isEmpty()) {
                    throw new CommandFormatException(
                            "Node name is missing or the format is wrong for the address string '"
                                    + address + "'");
                }

                int nameValueSep = node.indexOf(NODE_TYPE_NAME_SEPARATOR);
                if (nameValueSep < 0) {
                    // the prefix must end on the node type
                    builder.addNodeName(node);
//                    throw new CommandFormatException(
//                            "Couldn't locate node type/name separator in '"
//                                    + node + "' in command '" + cmd + "'");
                } else {

                    String nodeType = node.substring(0, nameValueSep).trim();
                    if (nodeType.isEmpty()) {
                        throw new CommandFormatException(
                                "The node type is missing for the node '"
                                        + node
                                        + "' or the format is wrong for the address string '"
                                        + address + "'");
                    }
                    if (!Util.isValidIdentifier(nodeType)) {
                        throw new CommandFormatException(
                                "The node type is not a valid identifier '"
                                        + nodeType
                                        + "' or the format is wrong for the address string '"
                                        + address + "'");
                    }

                    String nodeName = node.substring(nameValueSep + 1).trim();
                    if (nodeName.isEmpty()) {
                        throw new CommandFormatException(
                                "The node name is missing for the node '"
                                        + node
                                        + "' or the format is wrong for the address string '"
                                        + address + "'");
                    }
                    if (!Util.isValidIdentifier(nodeName)) {
                        throw new CommandFormatException(
                                "The node name is not a valid identifier '"
                                        + nodeName
                                        + "' or the format is wrong for the address string '"
                                        + address + "'");
                    }
                    builder.addNode(nodeType, nodeName);
                }
                nodeIndex = slashIndex + 1;
            }
        }

        String operation;
        if(argListStartIndex < 0) {
            //throw new CommandFormatException("Couldn't locate '" + ARG_LIST_START + "'. Command '" + cmd + "' doesn't follow the format " + FORMAT);
            int argListEndIndex = cmd.indexOf(ARG_LIST_END, addrSepIndex + 1);
            if(argListEndIndex != -1)
               throw new CommandFormatException("Couldn't locate '" + ARG_LIST_START + "' but found '" + ARG_LIST_END + "'. Command '" + cmd + "' doesn't follow the format " + FORMAT);
            operation = cmd.substring(addrSepIndex + 1);
        }
        else {
            operation = cmd.substring(addrSepIndex + 1, argListStartIndex).trim();
        }
        if(operation.isEmpty()) {
            throw new CommandFormatException("The operation name is missing: '" + cmd + "'");
        }
        if(!Util.isValidIdentifier(operation)) {
            throw new CommandFormatException("Operation name '" + operation + "' is not valid identifier or command '" + cmd + "' doesn't follow the format " + FORMAT);
        }
        builder.setOperationName(operation);

        if(argListStartIndex != -1) {
            int argListEndIndex = cmd.indexOf(ARG_LIST_END, argListStartIndex + 1);
            if (argListEndIndex < 0) {
                throw new CommandFormatException("Couldn't locate '"
                        + ARG_LIST_END + "'. Command '" + cmd
                        + "' doesn't follow the format " + FORMAT);
            }
            String args = cmd.substring(argListStartIndex + 1, argListEndIndex).trim();

            int argIndex = 0;
            while (argIndex < args.length()) {
                int argSepIndex = args.indexOf(ARG_SEPARATOR, argIndex);
                if (argSepIndex == -1) {
                    argSepIndex = args.length();
                }
                String arg = args.substring(argIndex, argSepIndex).trim();
                if (arg.isEmpty()) {
                    throw new CommandFormatException(
                            "An argument is missing or the command is in the wrong format: '"
                                    + cmd + "'");
                }

                int argNameValueSepIndex = arg.indexOf(ARG_NAME_VALUE_SEPARATOR);
                if (argNameValueSepIndex < 0) {
                    throw new CommandFormatException("Couldn't locate '"
                            + ARG_NAME_VALUE_SEPARATOR + "' in the argument '"
                            + arg + "'");
                }

                String argName = arg.substring(0, argNameValueSepIndex).trim();
                if (argName.isEmpty()) {
                    throw new CommandFormatException(
                            "The argument name is missing or the format is wrong for argument '"
                                    + arg + "'");
                }
                if (!Util.isValidIdentifier(argName)) {
                    throw new CommandFormatException(
                            "Argument name '"
                                    + argName
                                    + "' is not a valid identifier or the format is wrong for the argument list '"
                                    + args + "'");
                }
                String argValue = arg.substring(argNameValueSepIndex + 1).trim();
                if (argValue.isEmpty()) {
                    throw new CommandFormatException(
                            "The argument value is missing or the format is wrong for argument '"
                                    + arg + "'");
                }
                builder.addArgument(argName, argValue);

                argIndex = argSepIndex + 1;
            }
        }
    }
}
