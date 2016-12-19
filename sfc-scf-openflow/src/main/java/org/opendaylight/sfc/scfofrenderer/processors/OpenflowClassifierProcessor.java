/*
 * Copyright (c) 2016 Ericsson Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.sfc.scfofrenderer.processors;

import com.google.common.base.Strings;
import com.google.common.eventbus.Subscribe;
import org.opendaylight.controller.md.sal.binding.api.WriteTransaction;
import org.opendaylight.sfc.provider.api.SfcProviderRenderedPathAPI;
import org.opendaylight.sfc.provider.api.SfcProviderServiceForwarderAPI;
import org.opendaylight.sfc.scfofrenderer.utils.ClassifierHandler;
import org.opendaylight.sfc.scfofrenderer.utils.SfcNshHeader;
import org.opendaylight.sfc.scfofrenderer.utils.SfcScfMatch;
import org.opendaylight.sfc.scfofrenderer.utils.SfcScfOfUtils;
import org.opendaylight.sfc.scfofrenderer.flowgenerators.BareClassifier;
import org.opendaylight.sfc.scfofrenderer.flowgenerators.ClassifierInterface;
import org.opendaylight.sfc.scfofrenderer.logicalclassifier.ClassifierGeniusIntegration;
import org.opendaylight.sfc.scfofrenderer.flowgenerators.LogicallyAttachedClassifier;
import org.opendaylight.sfc.sfc_ovs.provider.SfcOvsUtil;
import org.opendaylight.sfc.util.openflow.transactional_writer.FlowDetails;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.acl.rev151001.Actions1;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.acl.rev151001.access.lists.acl.access.list.entries.ace.actions.sfc.action.AclRenderedServicePath;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.common.rev151017.RspName;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.common.rev151017.SffName;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.scf.rev140701.service.function.classifiers.service.function.classifier.SclServiceFunctionForwarder;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sff.rev140701.service.function.forwarders.ServiceFunctionForwarder;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sl.rev140701.data.plane.locator.locator.type.Ip;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.access.control.list.rev160218.access.lists.Acl;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.access.control.list.rev160218.access.lists.acl.access.list.entries.Ace;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.FlowBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.flow.Match;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class OpenflowClassifierProcessor {

    private WriteTransaction tx;

    // true if we're adding a classifier node, false if we're deleting it
    private boolean addClassifier = true;

    private ClassifierInterface classifierInterface;

    private BareClassifier bareClassifier;

    private LogicallyAttachedClassifier logicallyAttachedClassifier;

    private ClassifierHandler classifierHandler;

    private static final Logger LOG = LoggerFactory.getLogger(OpenflowClassifierProcessor.class);

    // hide the default constructor
    private OpenflowClassifierProcessor() {}

    public OpenflowClassifierProcessor(WriteTransaction theTx,
                                       LogicallyAttachedClassifier theLogicClassifier,
                                       BareClassifier theBareClassifier) {
        tx = theTx;
        classifierInterface = theBareClassifier;
        logicallyAttachedClassifier = theLogicClassifier;
        bareClassifier = theBareClassifier;
        classifierHandler = new ClassifierHandler();
    }

    /**
     * Process an Scf object, adding or removing the OF rules into the respective OVS
     * This method is called on result of classifier addition / removal.
     *
     * @param theClassifier the classifier node to be added
     * @param theAcl        the ACL we want to install in the classifier
     * @return              a List of {@link FlowDetails} having all the generated flows, which will be later installed
     */
    public List<FlowDetails> processClassifier(
            final SclServiceFunctionForwarder theClassifier,
            final Acl theAcl,
            final boolean addClassifierScenario) {
        addClassifier = addClassifierScenario;

        Optional<ServiceFunctionForwarder> sff = Optional.of(new SffName(theClassifier.getName()))
                .map(SfcProviderServiceForwarderAPI::readServiceFunctionForwarder);

        Optional<String> itfName = classifierHandler.getInterfaceNameFromClassifier(theClassifier);

        if (!sff.isPresent() || !itfName.isPresent()) {
            LOG.error("createdServiceFunctionClassifier: " +
                            "Cannot install ACL rules in classifier. SFF exists? {}; Interface exists? {}",
                    sff.isPresent(),
                    itfName.isPresent());
            return Collections.emptyList();
        }

        // choose which handler to use
        classifierInterface = classifierHandler.usesLogicalInterfaces(sff.get()) ?
                logicallyAttachedClassifier : bareClassifier.setSff(sff.get());

        Optional<String> nodeName = itfName.map(ifName -> classifierInterface.getNodeName(ifName))
                .orElse(Optional.empty());

        if(!nodeName.isPresent()) {
            LOG.error("createdServiceFunctionClassifier: Could not extract the node name from the OVS interface");
            return Collections.emptyList();
        }

        // bind/unbind the interface in genius, if the classifier is attached to a logical interface
        // (according to the scenario)
        if (classifierHandler.usesLogicalInterfaces(sff.get())) {
            if (addClassifierScenario) {
                ClassifierGeniusIntegration.performGeniusServiceBinding(tx, itfName.get());
                LOG.info("processClassifier - Bound interface {}", itfName.get());
            }
            else {
                ClassifierGeniusIntegration.performGeniusServiceUnbinding(tx, itfName.get());
                LOG.info("processClassifier - Unbound interface {}", itfName.get());
            }
        }

        return theAcl
                .getAccessListEntries()
                .getAce()
                .stream()
                .map(theAce -> processAce(nodeName.get(),
                        sff.get(),
                        theClassifier.getName(),
                        theAcl.getAclName(),
                        itfName.get(),
                        theAce))
                .reduce(new ArrayList<>(),
                        (dstList, theList) ->
                                Stream.concat(dstList.stream(), theList.stream()).collect(Collectors.toList()));
    }

    /**
     * Install an ACE entry, belonging to the given ACL, on the SFF identified through the specified nodeName.
     * This method is called on result of classifier addition / removal.
     *
     * @param nodeName      the compute node data-plane ID where the ACL is about to be written
     * @param theSff        the SFF to which the classifier is connected
     * @param theScfName    the name of the classifier
     * @param aclName       the name of the ACL
     * @param theIfName     the interface we want to classify
     * @param theAce        the ACE
     * @return              a List of {@link FlowDetails} having all the generated flows, which will be later installed
     */
    private List<FlowDetails> processAce(String nodeName, ServiceFunctionForwarder theSff, String theScfName,
                                        String aclName, String theIfName, Ace theAce) {

        List<FlowDetails> theFlows = new ArrayList<>();

        String ruleName = theAce.getRuleName();
        if (Strings.isNullOrEmpty(ruleName)) {
            LOG.error("processAce - ruleName is null; returning empty list");
            return Collections.emptyList();
        }

        LOG.info("processAce - NodeName: {}; IF name: {}", nodeName, theIfName);

        Optional<Long> inPort = classifierInterface.getInPort(theIfName, nodeName);
        // Build the match object if possible; throw a RuntimeException if the ACE is not correctly provisioned
        Match match = inPort.map(port -> String.format("%s:%s", nodeName, port))
                .map(NodeConnectorId::new)
                .map(connectorId -> new SfcScfMatch().setPortMatch(connectorId))
                .map(scfMatch -> scfMatch.setAclMatch(theAce.getMatches()))
                .orElseThrow(IllegalArgumentException::new)
                .build();

        Optional<RspName> rspName = Optional.ofNullable(theAce.getActions())
                .map(theActions -> theActions.getAugmentation(Actions1.class))
                .map(actions1 -> (AclRenderedServicePath) actions1.getSfcAction())
                .map(aclRsp -> new RspName(aclRsp.getRenderedServicePath()));

        SfcNshHeader nsh = SfcNshHeader.getSfcNshHeader(rspName.get());

        if (nsh == null) {
            LOG.error("processAce: nsh is null; returning empty list");
            return Collections.emptyList();
        }

        String flowKey = classifierHandler.buildFlowKeyName(theScfName, aclName, ruleName, ".out");

        // add a classifier
        if (addClassifier) {
            // write the flows into the classifier
            LOG.info("processAce - About to create flows");
            FlowBuilder initFlow, theOutFlow;
            initFlow = classifierInterface.initClassifierTable();
            theOutFlow  = classifierInterface.createClassifierOutFlow(flowKey, match, nsh, nodeName);

            theFlows.add(classifierHandler.addRspRelatedFlowIntoNode(nodeName, initFlow, nsh.getNshNsp()));
            theFlows.add(classifierHandler.addRspRelatedFlowIntoNode(nodeName, theOutFlow, nsh.getNshNsp()));

            // add DPDK flows
            Long dpdkPort = SfcOvsUtil.getDpdkOfPort(nodeName, null);
            if (dpdkPort != null) {
                LOG.info("processAce - Adding DPDK flows");
                theFlows.add(classifierHandler.addRspRelatedFlowIntoNode(
                        nodeName,
                        SfcScfOfUtils.initClassifierDpdkOutputFlow(dpdkPort), nsh.getNshNsp()));
                theFlows.add(classifierHandler.addRspRelatedFlowIntoNode(
                        nodeName,
                        SfcScfOfUtils.initClassifierDpdkInputFlow(nodeName, dpdkPort), nsh.getNshNsp()));
            }
        }
        else
        {
            LOG.info("processAce - About to delete the *out* flows");
            theFlows.add(classifierHandler.deleteFlowFromTable(nodeName, flowKey, ClassifierGeniusIntegration.getClassifierTable()));
        }

        // when the classifier is attached to a logical SFF, there's no need to process the reverse RSP, so we bail
        if (classifierHandler.usesLogicalInterfaces(theSff)) {
            return theFlows;
        }

        List<FlowDetails> theReverseRspFlows = processReverseRsp(rspName.get(),
                theScfName,
                aclName,
                nodeName,
                theAce.getRuleName(),
                inPort.get(),
                theSff);
        if (!theReverseRspFlows.isEmpty()) {
            theFlows.addAll(theReverseRspFlows);
        }

        LOG.debug("processAce - flow size: {}", theFlows.size());
        return theFlows;
    }

    /**
     * Add the classifier flows for reverse RSPs.
     *
     * @param theRspName    the RSP from which we want to derive the reverse RSP
     * @param theScfName    the name of the classifier who will process this reverse RSP traffic
     * @param theAclName    the name of the ACL
     * @param theNodeName   the compute node name where we will install the classifier flows for the reverse RSP
     * @param theRuleName   the name of the ACE
     * @param port          the output port of the classifier node
     * @param theSff        the SFF to which the classifier is connected
     * @return              a List of {@link FlowDetails} having all the generated flows, which will be later installed
     */
    protected List<FlowDetails> processReverseRsp(RspName theRspName,
                                                      String theScfName,
                                                      String theAclName,
                                                      String theNodeName,
                                                      String theRuleName,
                                                      long port,
                                                      ServiceFunctionForwarder theSff) {

        LOG.info("processReverseRsp - RSP name: {}", theRspName.getValue());
        List<FlowDetails> theFlows = new ArrayList<>();

        RspName reverseRspName = SfcProviderRenderedPathAPI.generateReversedPathName(theRspName);
        SfcNshHeader reverseNsh = SfcNshHeader.getSfcNshHeader(reverseRspName);

        if (reverseNsh == null) {
            LOG.warn("processReverseRsp: reverseNsh is null");
            return Collections.emptyList();
        } else {
            String flowKey = classifierHandler.buildFlowKeyName(theScfName, theAclName, theRuleName, ".in");

            if (addClassifier) {
                Optional<FlowDetails> theInFlow = Optional.ofNullable(classifierInterface.createClassifierInFlow(flowKey, reverseNsh, port))
                        .map(flowBuilder -> classifierHandler.addRspRelatedFlowIntoNode(theNodeName, flowBuilder, reverseNsh.getNshNsp()));
                if (theInFlow.isPresent()) {
                    LOG.info("processReverseRsp: Adding in flow to node {}", theNodeName);
                    theFlows.add(theInFlow.get());
                }
            }
            else {
                FlowDetails deleteRelayFlow =
                        classifierHandler.deleteFlowFromTable(theNodeName, flowKey, ClassifierGeniusIntegration.getClassifierTable());
                theFlows.add(deleteRelayFlow);
            }

            Optional<String> lastNodeName = Optional.ofNullable(reverseNsh.getSffName())
                    .filter(sffName -> !sffName.equals(theSff.getName()))
                    .map(SfcProviderServiceForwarderAPI::readServiceFunctionForwarder)
                    .map(SfcOvsUtil::getOpenFlowNodeIdForSff);

            if (!lastNodeName.isPresent()) {
                return theFlows;
            }

            FlowDetails relayFlow;
            if (addClassifier) {
                Ip ip = SfcOvsUtil.getSffVxlanDataLocator(theSff);
                Optional<SfcNshHeader> theNshHeader = Optional.of(reverseNsh)
                        .map(theReverseNsh -> theReverseNsh.setVxlanIpDst(ip.getIp().getIpv4Address()))
                        .map(theReverseNsh -> theReverseNsh.setVxlanUdpPort(ip.getPort()));

                relayFlow = theNshHeader
                        .map(theReverseNshHeader ->
                                classifierInterface.createClassifierRelayFlow(flowKey, theReverseNshHeader))
                        .map(flowBuilder ->
                                classifierHandler.addRspRelatedFlowIntoNode(lastNodeName.get(),
                                        flowBuilder,
                                        theNshHeader.get().getNshNsp()))
                        .orElseThrow(IllegalArgumentException::new);
            }
            else {
                relayFlow = classifierHandler.deleteFlowFromTable(lastNodeName.get(),
                                flowKey,
                                ClassifierGeniusIntegration.getClassifierTable());
            }
            theFlows.add(relayFlow);
        }
        return theFlows;
    }

    /**
     * Handler method of the {@link com.google.common.eventbus.EventBus class}
     *
     * @param theTx the new transaction being used by the OpenflowWriter to which this class is subscribed
     */
    @Subscribe public void refreshTransaction(WriteTransaction theTx) {
        LOG.debug("refreshTransaction - refreshing the transaction.");
        tx = theTx;
    }
}