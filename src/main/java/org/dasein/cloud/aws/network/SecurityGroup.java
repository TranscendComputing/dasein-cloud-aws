/**
 * Copyright (C) 2009-2012 enStratus Networks Inc
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.aws.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.Requirement;
import org.dasein.cloud.ResourceStatus;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.aws.compute.EC2Exception;
import org.dasein.cloud.aws.compute.EC2Method;
import org.dasein.cloud.compute.ComputeServices;
import org.dasein.cloud.compute.VirtualMachineSupport;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.network.RuleTargetType;
import org.dasein.cloud.network.Direction;
import org.dasein.cloud.network.Firewall;
import org.dasein.cloud.network.FirewallRule;
import org.dasein.cloud.network.FirewallSupport;
import org.dasein.cloud.network.Permission;
import org.dasein.cloud.network.Protocol;
import org.dasein.cloud.network.RuleTarget;
import org.dasein.cloud.util.APITrace;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SecurityGroup implements FirewallSupport {
	static private final Logger logger = AWSCloud.getLogger(SecurityGroup.class);

    static private boolean isIP(@Nonnull String test) {
        String[] parts = test.split("\\.");

        if( parts.length != 4 ) {
            return false;
        }
        for( String part : parts ) {
            try {
                Integer x = Integer.parseInt(part);

                if( x < 0 || x > 255 ) {
                    return false;
                }
            }
            catch( NumberFormatException e ) {
                return false;
            }
        }
        return true;
    }

	private AWSCloud provider = null;
	
	SecurityGroup(AWSCloud provider) {
		this.provider = provider;
	}
	
	@Override
	public @Nonnull String authorize(@Nonnull String securityGroupId, @Nonnull String cidr, @Nonnull Protocol protocol, int startPort, int endPort) throws CloudException, InternalException {
        return authorize(securityGroupId, Direction.INGRESS, cidr, protocol, startPort, endPort);
    }

    @Override
    public @Nonnull String authorize(@Nonnull String firewallId, @Nonnull Direction direction, @Nonnull String cidr, @Nonnull Protocol protocol, int beginPort, int endPort) throws CloudException, InternalException {
        return authorize(firewallId, direction, Permission.ALLOW, cidr, protocol, RuleTarget.getGlobal(""), beginPort, endPort);
    }

    @Override
    public @Nonnull String authorize(@Nonnull String firewallId, @Nonnull Direction direction, @Nonnull Permission permission, @Nonnull String cidr, @Nonnull Protocol protocol, int beginPort, int endPort) throws CloudException, InternalException {
        return authorize(firewallId, direction, permission, cidr, protocol, RuleTarget.getGlobal(""), beginPort, endPort);
    }

    @Override
    public @Nonnull String authorize(@Nonnull String firewallId, @Nonnull Direction direction, @Nonnull Permission permission, @Nonnull String cidr, @Nonnull Protocol protocol, @Nonnull RuleTarget destination, int beginPort, int endPort) throws CloudException, InternalException {
        APITrace.begin(provider, "authorizeSecurityGroupRule");
        try {
            if( Permission.DENY.equals(permission) ) {
                throw new OperationNotSupportedException("AWS does not support DENY rules");
            }
            if( !destination.getRuleTargetType().equals(RuleTargetType.GLOBAL) ) {
                throw new OperationNotSupportedException("AWS does not support discreet routing of rules");
            }
            Firewall fw = getFirewall(firewallId);

            if( fw == null ) {
                throw new CloudException("No such firewall: " + firewallId);
            }
            if( direction.equals(Direction.EGRESS) && fw.getProviderVlanId() == null ) {
                throw new OperationNotSupportedException("AWS does not support EGRESS rules for non-VPC security groups");
            }
            String action = (direction.equals(Direction.INGRESS) ? EC2Method.AUTHORIZE_SECURITY_GROUP_INGRESS : EC2Method.AUTHORIZE_SECURITY_GROUP_EGRESS);
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), action);
            boolean group = false;
            EC2Method method;
            NodeList blocks;
            Document doc;

            if( cidr.indexOf('/') == -1 ) {
                // might be a security group
                if( isIP(cidr) ) {
                    cidr = cidr + "/32";
                }
                else {
                    group = true;
                }
            }
            if( provider.getEC2Provider().isEucalyptus() ) {
                parameters.put("GroupName", firewallId);
                parameters.put("IpProtocol", protocol.name().toLowerCase());
                parameters.put("FromPort", String.valueOf(beginPort));
                parameters.put("ToPort", endPort == -1 ? String.valueOf(beginPort) : String.valueOf(endPort));
                if( group ) {
                    parameters.put("GroupName", cidr);
                }
                else {
                    parameters.put("CidrIp", cidr);
                }

            }
            else {
                parameters.put("GroupId", firewallId);
                parameters.put("IpPermissions.1.IpProtocol", protocol.name().toLowerCase());
                parameters.put("IpPermissions.1.FromPort", String.valueOf(beginPort));
                parameters.put("IpPermissions.1.ToPort", endPort == -1 ? String.valueOf(beginPort) : String.valueOf(endPort));
                if( group ) {
                    if( cidr.startsWith("sg-") ) {
                        parameters.put("IpPermissions.1.Groups.1.GroupId", cidr);
                    }
                    else {
                        parameters.put("IpPermissions.1.Groups.1.GroupName", cidr);
                    }
                }
                else {
                    parameters.put("IpPermissions.1.IpRanges.1.CidrIp", cidr);
                }
            }
            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.equals("InvalidPermission.Duplicate") ) {
                    return FirewallRule.getInstance(null, firewallId, cidr, direction, protocol, Permission.ALLOW, RuleTarget.getGlobal(""), beginPort, endPort).getProviderRuleId();
                }
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if( blocks.getLength() > 0 ) {
                if( !blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true") ) {
                    throw new CloudException("Failed to authorize security group rule without explanation.");
                }
            }
            return FirewallRule.getInstance(null, firewallId, cidr, direction, protocol, Permission.ALLOW, RuleTarget.getGlobal(""), beginPort, endPort).getProviderRuleId();
        }
        finally {
            APITrace.end();
        }
    }

	@Override
	public @Nonnull
	String authorize(@Nonnull String firewallId,
			@Nullable String ingressFirewallId,
			@Nullable String ingressFirewallOwnerId,
			@Nonnull Protocol protocol, int beginPort, int endPort)
			throws CloudException, InternalException {
//		Logger logger = NovaOpenStack.getLogger(NovaSecurityGroup.class, "std");
//
//		if (logger.isTraceEnabled()) {
//			logger.trace("ENTER: " + NovaSecurityGroup.class.getName()
//					+ ".authorize(" + firewallId + "," + ingressFirewallId
//					+ "," + ingressFirewallOwnerId + "," + protocol + ","
//					+ beginPort + "," + endPort + ")");
//		}
//		try {
//			ProviderContext ctx = provider.getContext();
//
//			if (ctx == null) {
//				logger.error("No context exists for this request");
//				throw new InternalException(
//						"No context exists for this request");
//			}
//			HashMap<String, Object> wrapper = new HashMap<String, Object>();
//			HashMap<String, Object> json = new HashMap<String, Object>();
//			NovaMethod method = new NovaMethod(provider);
//
//			json.put("ip_protocol", protocol.name().toLowerCase());
//			json.put("from_port", beginPort);
//			json.put("to_port", endPort);
//			json.put("parent_group_id", firewallId);
//			json.put("group_id", ingressFirewallId);
//			wrapper.put("security_group_rule", json);
//			JSONObject result = method.postServers("/os-security-group-rules",
//					null, new JSONObject(wrapper), false);
//
//			if (result != null && result.has("security_group_rule")) {
//				try {
//					JSONObject rule = result
//							.getJSONObject("security_group_rule");
//
//					return rule.getString("id");
//				} catch (JSONException e) {
//					logger.error("Invalid JSON returned from rule creation: "
//							+ e.getMessage());
//					throw new CloudException(e);
//				}
//			}
//			logger.error("authorize(): No firewall rule was created by the create attempt, and no error was returned");
//			throw new CloudException("No firewall rule was created");
//		} finally {
//			if (logger.isTraceEnabled()) {
//				logger.trace("EXIT: " + NovaSecurityGroup.class.getName()
//						+ ".authorize()");
//			}
//		}
		return null;
	}
    @Override
	public @Nonnull String create(@Nonnull String name, @Nonnull String description) throws InternalException, CloudException {
        APITrace.begin(provider, "createSecurityGroup");
        try {
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.CREATE_SECURITY_GROUP);
            EC2Method method;
            NodeList blocks;
            Document doc;

            name = getUniqueName(name);
            parameters.put("GroupName", name);
            parameters.put("GroupDescription", description);
            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            if( provider.getEC2Provider().isEucalyptus() ) {
                return name;
            }
            else {
                blocks = doc.getElementsByTagName("groupId");
                if( blocks.getLength() > 0 ) {
                    return blocks.item(0).getFirstChild().getNodeValue().trim();
                }
                throw new CloudException("Failed to create security group without explanation.");
            }
        }
        finally {
            APITrace.end();
        }
	}
 	
   @Override
   public @Nonnull String createInVLAN(@Nonnull String name, @Nonnull String description, @Nonnull String providerVlanId) throws InternalException, CloudException {
       APITrace.begin(provider, "createSecurityGroupInVLAN");
       try {
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.CREATE_SECURITY_GROUP);
            EC2Method method;
            NodeList blocks;
            Document doc;

            name = getUniqueName(name);
            parameters.put("GroupName", name);
            parameters.put("GroupDescription", description);
            parameters.put("VpcId", providerVlanId);
            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("groupId");
            if( blocks.getLength() > 0 ) {
                return blocks.item(0).getFirstChild().getNodeValue().trim();
            }
            throw new CloudException("Failed to create security group without explanation.");
       }
       finally {
           APITrace.end();
       }
   }
	   
	@Override
	public void delete(@Nonnull String securityGroupId) throws InternalException, CloudException {
        APITrace.begin(provider, "deleteSecurityGroup");
        try {
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.DELETE_SECURITY_GROUP);
            EC2Method method;
            NodeList blocks;
            Document doc;

            if( provider.getEC2Provider().isEucalyptus() ) {
                parameters.put("GroupName", securityGroupId);
            }
            else {
                parameters.put("GroupId", securityGroupId);
            }
            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if( blocks.getLength() > 0 ) {
                if( !blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true") ) {
                    throw new CloudException("Failed to delete security group without explanation.");
                }
            }
        }
        finally {
            APITrace.end();
        }
	}

	@Override
	public @Nullable Firewall getFirewall(@Nonnull String securityGroupId) throws InternalException, CloudException {
        APITrace.begin(provider, "getFirewall");
        try {
            ProviderContext ctx = provider.getContext();

            if( ctx == null ) {
                throw new CloudException("No context has been established for this request");
            }
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.DESCRIBE_SECURITY_GROUPS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            if( provider.getEC2Provider().isEucalyptus() ) {
                parameters.put("GroupName.1", securityGroupId);
            }
            else {
                parameters.put("GroupId.1", securityGroupId);
            }
            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.startsWith("InvalidGroup") ) {
                    return null;
                }
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("securityGroupInfo");
            for( int i=0; i<blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j=0; j<items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("item") ) {
                        Firewall firewall = toFirewall(ctx, item);

                        if( firewall != null && securityGroupId.equals(firewall.getProviderFirewallId()) ) {
                            return firewall;
                        }
                    }
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
	}

	@Override
	public @Nonnull String getProviderTermForFirewall(@Nonnull Locale locale) {
		return "security group";
	}

	@Override
	public @Nonnull Collection<FirewallRule> getRules(@Nonnull String securityGroupId) throws InternalException, CloudException {
        APITrace.begin(provider, "getSecurityGroupRules");
        try {
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.DESCRIBE_SECURITY_GROUPS);
            ArrayList<FirewallRule> list = new ArrayList<FirewallRule>();
            EC2Method method;
            NodeList blocks;
            Document doc;

            if( provider.getEC2Provider().isEucalyptus() ) {
                parameters.put("GroupName.1", securityGroupId);
            }
            else {
                parameters.put("GroupId.1", securityGroupId);
            }
            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.startsWith("InvalidGroup") ) {
                    return Collections.emptyList();
                }
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("securityGroupInfo");
            for( int i=0; i<blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j=0; j<items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("item") ) {
                        NodeList attrs = item.getChildNodes();

                        for( int k=0; k<attrs.getLength(); k++ ) {
                            Node attr = attrs.item(k);

                            if( attr.getNodeName().equals("ipPermissions") ) {
                                NodeList subList = attr.getChildNodes();

                                for( int l=0; l<subList.getLength(); l++ ) {
                                    Node sub = subList.item(l);

                                    if( sub.getNodeName().equals("item") ) {
                                        list.addAll(toFirewallRules(securityGroupId, sub, Direction.INGRESS));
                                    }
                                }
                            }
                            else if( attr.getNodeName().equals("ipPermissionsEgress") ) {
                                NodeList subList = attr.getChildNodes();

                                for( int l=0; l<subList.getLength(); l++ ) {
                                    Node sub = subList.item(l);

                                    if( sub.getNodeName().equals("item") ) {
                                        list.addAll(toFirewallRules(securityGroupId, sub, Direction.EGRESS));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return list;
        }
        finally {
            APITrace.end();
        }
	}

	private @Nonnull String getUniqueName(@Nonnull String name) throws InternalException, CloudException {
        StringBuilder str = new StringBuilder();
        
        for( int i=0; i<name.length(); i++ ) {
            char c = name.charAt(i);
            
            if( i == 0 && Character.isDigit(c) ) {
                str.append("e-");
                str.append(c);
            }
            else if( i == 0 && Character.isLetter(c) ) {
                str.append(c);
            }
            else if((Character.isLetterOrDigit(c) || c == '-' || c == '_') ) {
                str.append(c);
            }
        }
        if( str.length() < 1 ) {
            return "new-group";
        }
        String baseName = str.toString();
        String withName = baseName;
        int count = 1;
        boolean found;
        char c = 'a';
        
        do {
            found = false;
            for( Firewall fw : list() ) {
                String id = fw.getProviderFirewallId();
                
                if( id == null ) {
                    continue;
                }
                if( id.equals(withName) ) {
                    found = true;
                    if( count == 1 ) {
                        withName = baseName + "-" + String.valueOf(c);
                    }
                    else {
                        withName = baseName + String.valueOf(c);
                    }
                    if( c == 'z' ) {
                        if( count == 1 ) {
                            baseName = baseName + "-a";
                        }
                        else {
                            baseName = baseName + "a";
                        }
                        c = 'a';
                        count++;
                        if( count > 10 ) {
                            throw new CloudException("Could not generate a unique firewall name from " + baseName);
                        }
                    }
                    else {
                        c++;
                    }
                    break;
                }                        
            }                        
        } while( found ); 		
        return withName;
	}

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(provider, "isSubscribedSecurityGroup");
        try {
            ComputeServices svc = provider.getComputeServices();
        
            if( svc == null ) {
                return false;
            }
            VirtualMachineSupport support = svc.getVirtualMachineSupport();

            return (support != null && support.isSubscribed());
        }
        finally {
            APITrace.end();
        }
    }

	@Override
	public @Nonnull Collection<Firewall> list() throws InternalException, CloudException {
        APITrace.begin(provider, "listSecurityGroups");
        try {
            ProviderContext ctx = provider.getContext();

            if( ctx == null ) {
                throw new CloudException("No context has been established for this request");
            }
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.DESCRIBE_SECURITY_GROUPS);
            ArrayList<Firewall> list = new ArrayList<Firewall>();
            EC2Method method;
            NodeList blocks;
            Document doc;

            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("securityGroupInfo");
            for( int i=0; i<blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j=0; j<items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("item") ) {
                        Firewall firewall = toFirewall(ctx, item);

                        if( firewall != null ) {
                            list.add(firewall);
                        }
                    }
                }
            }
            return list;
        }
        finally {
            APITrace.end();
        }
	}

    @Override
    public @Nonnull Iterable<ResourceStatus> listFirewallStatus() throws InternalException, CloudException {
        APITrace.begin(provider, "listSecurityGroupStatus");
        try {
            ProviderContext ctx = provider.getContext();

            if( ctx == null ) {
                throw new CloudException("No context has been established for this request");
            }
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.DESCRIBE_SECURITY_GROUPS);
            ArrayList<ResourceStatus> list = new ArrayList<ResourceStatus>();
            EC2Method method;
            NodeList blocks;
            Document doc;

            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("securityGroupInfo");
            for( int i=0; i<blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j=0; j<items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("item") ) {
                        ResourceStatus status = toStatus(item);

                        if( status != null ) {
                            list.add(status);
                        }
                    }
                }
            }
            return list;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<RuleTargetType> listSupportedDestinationTypes(boolean inVlan) throws InternalException, CloudException {
        return Collections.singletonList(RuleTargetType.GLOBAL);
    }

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        if( action.equals(FirewallSupport.ANY) ) {
            return new String[] { EC2Method.EC2_PREFIX + "*" };
        }
        else if( action.equals(FirewallSupport.AUTHORIZE) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.AUTHORIZE_SECURITY_GROUP_INGRESS, EC2Method.EC2_PREFIX + EC2Method.AUTHORIZE_SECURITY_GROUP_EGRESS };
        }
        else if( action.equals(FirewallSupport.CREATE_FIREWALL) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.CREATE_SECURITY_GROUP };
        }
        else if( action.equals(FirewallSupport.GET_FIREWALL) || action.equals(FirewallSupport.LIST_FIREWALL) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_SECURITY_GROUPS };
        }
        else if( action.equals(FirewallSupport.REMOVE_FIREWALL) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DELETE_SECURITY_GROUP };
        }
        else if( action.equals(FirewallSupport.REVOKE) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.REVOKE_SECURITY_GROUP_INGRESS, EC2Method.EC2_PREFIX + EC2Method.REVOKE_SECURITY_GROUP_EGRESS };
        }
        return new String[0];
    }

    @Override
    public void revoke(@Nonnull String providerFirewallRuleId) throws InternalException, CloudException {
        FirewallRule rule = FirewallRule.parseId(providerFirewallRuleId);

        if( rule == null ) {
            throw new CloudException("Unable to parse rule ID: " + providerFirewallRuleId);
        }
        revoke(rule.getFirewallId(), rule.getDirection(), rule.getPermission(), rule.getSource(), rule.getProtocol(), rule.getTarget(), rule.getStartPort(), rule.getEndPort());
    }

	@Override
	public void revoke(@Nonnull String securityGroupId, @Nonnull String cidr, @Nonnull Protocol protocol, int startPort, int endPort) throws CloudException, InternalException {
        revoke(securityGroupId, Direction.INGRESS, Permission.ALLOW, cidr, protocol, RuleTarget.getGlobal(""), startPort, endPort);
    }

    @Override
    public void revoke(@Nonnull String firewallId, @Nonnull Direction direction, @Nonnull String cidr, @Nonnull Protocol protocol, int beginPort, int endPort) throws CloudException, InternalException {
        revoke(firewallId, direction, Permission.ALLOW, cidr, protocol, RuleTarget.getGlobal(""), beginPort, endPort);
    }

    @Override
    public void revoke(@Nonnull String firewallId, @Nonnull Direction direction, @Nonnull Permission permission, @Nonnull String cidr, @Nonnull Protocol protocol, int beginPort, int endPort) throws CloudException, InternalException {
        revoke(firewallId, direction, permission, cidr, protocol, RuleTarget.getGlobal(""), beginPort, endPort);
    }

    @Override
    public void revoke(@Nonnull String firewallId, @Nonnull Direction direction, @Nonnull Permission permission, @Nonnull String cidr, @Nonnull Protocol protocol, @Nonnull RuleTarget destination, int beginPort, int endPort) throws CloudException, InternalException {
        APITrace.begin(provider, "revokeSecurityGroupRule");
        try {
            if( Permission.DENY.equals(permission) || !destination.getRuleTargetType().equals(RuleTargetType.GLOBAL) ) {
                return;
            }
            String action = (direction.equals(Direction.INGRESS) ? EC2Method.REVOKE_SECURITY_GROUP_INGRESS : EC2Method.REVOKE_SECURITY_GROUP_EGRESS);
            Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), action);
            boolean group = false;
            EC2Method method;
            Document doc;

            if( cidr.indexOf('/') == -1 ) {
                // might be a security group
                if( isIP(cidr) ) {
                    cidr = cidr + "/32";
                }
                else {
                    group = true;
                }
            }
            if( provider.getEC2Provider().isEucalyptus() ) {
                parameters.put("GroupName", firewallId);
                parameters.put("IpProtocol", protocol.name().toLowerCase());
                parameters.put("FromPort", String.valueOf(beginPort));
                parameters.put("ToPort", endPort == -1 ? String.valueOf(beginPort) : String.valueOf(endPort));
                if( group ) {
                    parameters.put("GroupName", cidr);
                }
                else {
                    parameters.put("CidrIp", cidr);
                }
            }
            else {
                parameters.put("GroupId", firewallId);
                parameters.put("IpPermissions.1.IpProtocol", protocol.name().toLowerCase());
                parameters.put("IpPermissions.1.FromPort", String.valueOf(beginPort));
                parameters.put("IpPermissions.1.ToPort", endPort == -1 ? String.valueOf(beginPort) : String.valueOf(endPort));
                if( group ) {
                    parameters.put("IpPermissions.1.Groups.1.GroupId", cidr);
                }
                else {
                    parameters.put("IpPermissions.1.IpRanges.1.CidrIp", cidr);
                }
            }
            method = new EC2Method(provider, provider.getEc2Url(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new CloudException(e);
            }
            method.checkSuccess(doc.getElementsByTagName("return"));
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public boolean supportsFirewallSources() throws CloudException, InternalException {
        return true;
    }

    @Override
    public boolean supportsRules(@Nonnull Direction direction, @Nonnull Permission permission, boolean inVlan) throws CloudException, InternalException {
        return (permission.equals(Permission.ALLOW) && (!(inVlan && provider.getEC2Provider().isEucalyptus()) && (inVlan || direction.equals(Direction.INGRESS))));
    }

    private @Nullable Firewall toFirewall(@Nonnull ProviderContext ctx, @Nullable Node node) {
        if( node == null ) {
            return null;
        }
        String fwName = null, fwId = null, fwDesc = null;
		NodeList attrs = node.getChildNodes();
		Firewall firewall = new Firewall();
        String regionId = ctx.getRegionId();
		String vpcId = null;

        if( regionId == null ) {
            return null;
        }
        firewall.setRegionId(regionId);
        firewall.setAvailable(true);
        firewall.setActive(true);
		for( int i=0; i<attrs.getLength(); i++ ) {
			Node attr = attrs.item(i);
			String name;
			
			name = attr.getNodeName();
			if( name.equals("groupName") ) {
                fwName = attr.getFirstChild().getNodeValue().trim();
			}
			else if( name.equals("groupDescription") ) {
				fwDesc = attr.getFirstChild().getNodeValue().trim();
			}
			else if( name.equals("groupId") ) {
                fwId = attr.getFirstChild().getNodeValue().trim();			    
			}
			else if( name.equals("vpcId") ) {
			    if( attr.hasChildNodes() ) {
			        vpcId = attr.getFirstChild().getNodeValue();
			        if( vpcId != null ) {
			            vpcId = vpcId.trim();
			        }
			    }
			}
		}
        if( fwId == null ) {
            if( fwName == null ) {
                return null;
            }
            fwId = fwName;
        }
		if( fwName == null ) {
		    fwName = fwId;
		}
        firewall.setProviderFirewallId(fwId);
        firewall.setName(fwName);
        if( fwDesc == null ) {
            fwDesc = fwName;
        }
        firewall.setDescription(fwDesc);
		if( vpcId != null ) {
		    firewall.setName(firewall.getName() + " (VPC " + vpcId + ")");
		    firewall.setProviderVlanId(vpcId);
		}
		return firewall;
	}
	
	private @Nonnull Collection<FirewallRule> toFirewallRules(@Nonnull String securityGroupId, @Nullable Node node, @Nonnull Direction direction) {
	    ArrayList<FirewallRule> rules = new ArrayList<FirewallRule>();
        
        if( node == null ) {
            return rules;
        }
	    ArrayList<String> cidrs = new ArrayList<String>();
		NodeList attrs = node.getChildNodes();
        int startPort = -2, endPort = -2;
		Protocol protocol = Protocol.TCP;
		
		for( int i=0; i<attrs.getLength(); i++ ) {
			Node attr = attrs.item(i);
			String name;
			
			name = attr.getNodeName();
			if( name.equals("ipProtocol") ) {
			    String val = attr.getFirstChild().getNodeValue().trim();
			    
			    if( !val.equals("") && !val.equals("-1") ) {
			        protocol = Protocol.valueOf(attr.getFirstChild().getNodeValue().trim().toUpperCase());
			    }
			    else {
			        protocol = Protocol.ICMP;
			    }
			}
			else if( name.equals("fromPort") ) {
				startPort = Integer.parseInt(attr.getFirstChild().getNodeValue().trim());
			}
			else if( name.equals("toPort") ) {
				endPort = Integer.parseInt(attr.getFirstChild().getNodeValue().trim());
			}
            else if( name.equals("groups") && attr.hasChildNodes() ) {
                NodeList children = attr.getChildNodes();

                for( int j=0; j<children.getLength(); j++ ) {
                    Node child = children.item(j);

                    if( child.getNodeName().equals("item") ) {
                        if( child.hasChildNodes() ) {
                            NodeList targets = child.getChildNodes();
                            String groupId = null, groupName = null;

                            for( int k=0; k<targets.getLength(); k++ ) {
                                Node group = targets.item(k);

                                if( group.getNodeName().equals("groupId") ) {
                                    groupId = group.getFirstChild().getNodeValue().trim();
                                }
                                if( group.getNodeName().equals("groupName") ) {
                                    groupName = group.getFirstChild().getNodeValue().trim();
                                }
                            }
                            if( groupId != null ) {
                                cidrs.add(groupId);
                            }
                            else if( groupName != null ) {
                                cidrs.add(groupName);
                            }
                        }
                    }
                }
            }
			else if( name.equals("ipRanges") ) {
				if( attr.hasChildNodes() ) {
					NodeList children = attr.getChildNodes();
				
					for( int j=0; j<children.getLength(); j++ ) {
						Node child = children.item(j);
					
						if( child.getNodeName().equals("item") ) {
						    if( child.hasChildNodes() ) { 
						        NodeList targets = child.getChildNodes();
						        
						        for( int k=0; k<targets.getLength(); k++ ) {
						            Node cidr = targets.item(k);

						            if( cidr.getNodeName().equals("cidrIp") ) {
						                cidrs.add(cidr.getFirstChild().getNodeValue());
						            }
						        }
						    }
						}
					}
				}
			}
		}
		for( String cidr : cidrs ) {
		    rules.add(FirewallRule.getInstance(null, securityGroupId, cidr, direction, protocol, Permission.ALLOW, RuleTarget.getGlobal(""), startPort, endPort));
		}
		return rules;		
	}

    private @Nullable ResourceStatus toStatus(@Nullable Node node) {
        if( node == null ) {
            return null;
        }
        NodeList attrs = node.getChildNodes();
        String fwId = null, fwName = null;

        for( int i=0; i<attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            String name;

            name = attr.getNodeName();
            if( name.equals("groupName") ) {
                fwName = attr.getFirstChild().getNodeValue().trim();
            }
            else if( name.equals("groupId") ) {
                fwId = attr.getFirstChild().getNodeValue().trim();
            }
        }
        if( fwId == null && fwName == null ) {
            return null;
        }
        if( fwId == null ) {
            fwId = fwName;
        }
        return new ResourceStatus(fwId, true);
    }

	@Override
	public @Nonnull
	String authorize(@Nonnull String firewallId, @Nonnull Direction direction,
			@Nonnull Permission permission, @Nonnull RuleTarget sourceEndpoint,
			@Nonnull Protocol protocol,
			@Nonnull RuleTarget destinationEndpoint, int beginPort,
			int endPort, @Nonnegative int precedence) throws CloudException,
			InternalException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public @Nonnull
	Requirement identifyPrecedenceRequirement(boolean inVlan)
			throws InternalException, CloudException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isZeroPrecedenceHighest() throws InternalException,
			CloudException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public @Nonnull
	Iterable<Direction> listSupportedDirections(boolean inVlan)
			throws InternalException, CloudException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public @Nonnull
	Iterable<Permission> listSupportedPermissions(boolean inVlan)
			throws InternalException, CloudException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public @Nonnull
	Iterable<RuleTargetType> listSupportedSourceTypes(boolean inVlan)
			throws InternalException, CloudException {
		// TODO Auto-generated method stub
		return null;
	}
}
