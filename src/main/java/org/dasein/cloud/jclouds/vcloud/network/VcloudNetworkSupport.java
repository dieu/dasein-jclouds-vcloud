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

package org.dasein.cloud.jclouds.vcloud.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.jclouds.vcloud.VcloudDirector;
import org.dasein.cloud.network.NetworkInterface;
import org.dasein.cloud.network.Subnet;
import org.dasein.cloud.network.VLANSupport;
import org.dasein.cloud.network.VLAN;
import org.jclouds.rest.ApiContext;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.vcloud.VCloudApi;
import org.jclouds.vcloud.VCloudMediaType;
import org.jclouds.vcloud.domain.NetworkConnection;
import org.jclouds.vcloud.domain.Org;
import org.jclouds.vcloud.domain.ReferenceType;
import org.jclouds.vcloud.domain.Vm;
import org.jclouds.vcloud.domain.network.IpScope;
import org.jclouds.vcloud.domain.network.OrgNetwork;
import org.jclouds.vcloud.features.NetworkApi;

import javax.annotation.Nonnull;

public class VcloudNetworkSupport implements VLANSupport {
	static private final Logger logger = Logger.getLogger(VcloudNetworkSupport.class);

	private VcloudDirector provider;

	VcloudNetworkSupport(VcloudDirector provider) { this.provider = provider; }

	@Override
	public boolean allowsNewVlanCreation() throws CloudException, InternalException {
		return false;
	}

	@Override
	public int getMaxVlanCount() throws CloudException, InternalException {
		return 0;
	}

	@Override
	public VLAN getVlan(String vlanId) throws CloudException, InternalException {
        ApiContext<VCloudApi> ctx = provider.getCloudClient();
        return toVlan(ctx, ctx.getApi().getNetworkApi().getNetwork(provider.toHref(ctx, vlanId)));
	}

    public VLAN getVlanByName(String name) throws CloudException, InternalException {
        ApiContext<VCloudApi> ctx = provider.getCloudClient();
        ReferenceType vlan = provider.getOrg().getNetworks().get(name);
        return toVlan(ctx, ctx.getApi().getNetworkApi().getNetwork(vlan.getHref()));
    }

	@Override
	public boolean isSubscribed() throws CloudException, InternalException {
		try {
			provider.getOrg().getNetworks();
			return true;
		}
		catch( AuthorizationException e ) {
			return false;
		}
		catch( RuntimeException e ) {
			throw new CloudException(e);
		}
	}

	@Override
	public Iterable<NetworkInterface> listNetworkInterfaces(String forVmId) throws CloudException, InternalException {
		ApiContext<VCloudApi> ctx = provider.getCloudClient();

		try {
			try {
				Map<String,ReferenceType> map = provider.getOrg().getNetworks();
				ArrayList<NetworkInterface> list = new ArrayList<NetworkInterface>();
				ArrayList<OrgNetwork> networks = new ArrayList<OrgNetwork>();
				Vm vm = ctx.getApi().getVmApi().getVm(provider.toHref(ctx, forVmId));
				NetworkConnection def = null;

				if( map != null ) {
					for( ReferenceType t : map.values() ) {
						if( t.getType().equals(VCloudMediaType.NETWORK_XML) ) {
							OrgNetwork network = ctx.getApi().getNetworkApi().getNetwork(t.getHref());

							if( network != null ) {
								networks.add(network);
							}
						}
					}
				}
				for( NetworkConnection c : vm.getNetworkConnectionSection().getConnections() ) {
					NetworkInterface nic = new NetworkInterface();

					nic.setProviderNetworkInterfaceId(c.getMACAddress());
					nic.setIpAddress(c.getIpAddress());
					nic.setProviderVirtualMachineId(forVmId);
					for( OrgNetwork network : networks ) {
						if( network.getName().equals(c.getNetwork()) ) {
							IpScope scope = network.getConfiguration().getIpScope();

							if( def == null || def.getNetworkConnectionIndex() > c.getNetworkConnectionIndex() ) {
								def = c;
							}
							nic.setGatewayAddress(scope.getGateway());
							nic.setNetmask(scope.getNetmask());
							nic.setProviderVlanId(provider.toId(ctx, network.getHref()));
						}
					}
				}
				if( def != null ) {
					for( NetworkInterface nic : list ) {
						if( def.getMACAddress().equals(nic.getProviderNetworkInterfaceId()) ) {
							nic.setDefaultRoute(true);
						}
					}
				}
				return list;
			}
			catch( RuntimeException e ) {
				logger.error("Error listing network interfaces for " + forVmId + ": " + e.getMessage());
				if( logger.isDebugEnabled() ) {
					e.printStackTrace();
				}
				throw new CloudException(e);
			}
		}
		finally {
			ctx.close();
		}
	}

	@Override
	public Iterable<VLAN> listVlans() throws CloudException, InternalException {
		logger.trace("enter - listVlans()");
		ApiContext<VCloudApi> ctx = provider.getCloudClient();

		try {
			ArrayList<VLAN> list = new ArrayList<VLAN>();
			Org org = provider.getOrg();
			Map<String,ReferenceType> map = null;
			if (org != null) {
				map = org.getNetworks();
			}
			else {
				logger.warn("listVlans(): Org is null");
			}


			if( map == null ) {
				return Collections.emptyList();
			}
			logger.debug("map size = " + map.size());
			for( ReferenceType type : map.values() ) {
				if( type.getType().equals(VCloudMediaType.NETWORK_XML) ) {
					VCloudApi client = ctx.getApi();
					NetworkApi networkClient = null;
					if (client != null){
						networkClient = client.getNetworkApi();
					}
					else {
						logger.warn("listVlans(): VCloudApi is null");
					}
					if (networkClient != null){
						logger.debug("listVlans(): type name = " + type.getName());
						logger.debug("listVlans(): type uri = " + type.getHref());
						logger.debug("listVlans(): type type = " + type.getType());
						if (type.getHref() != null) {
							OrgNetwork network = null;
							try {
								network = networkClient.getNetwork(type.getHref());
							}
							catch( RuntimeException e ) {
								logger.error("Error getting network for " + type.getName() + " - " + type.getHref() + ": " + e.getMessage());
								if( logger.isDebugEnabled() ) {
									e.printStackTrace();
								}
								continue;
							}
							VLAN vlan = toVlan(ctx, network);
							if( vlan != null ) {
								list.add(vlan);
							}
						}
					}
					else {
						logger.warn("listVlans(): NetworkClient is null");
					}
				}
			}
			return list;
		}
		finally {
			ctx.close();
			logger.trace("exit - listVlans()");
		}
	}

	@Override
	public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
		return new String[0];
	}

	@Override
	public void removeVlan(String vlanId) throws CloudException, InternalException {
		throw new OperationNotSupportedException("Network provisioning is not supported");
	}

	private String toCidr(String gateway, String netmask) {
		String[] dots = netmask.split("\\.");
		int cidr = 0;

		for( String item : dots ) {
			int x = Integer.parseInt(item);

			for( ; x > 0 ; x = (x<<1)%256 ) {
				cidr++;
			}
		}
		StringBuilder network = new StringBuilder();

		dots = gateway.split("\\.");
		int start = 0;

		for( String item : dots ) {
			if( ((start+8) < cidr) || cidr == 0 ) {
				network.append(item);
			}
			else {
				int addresses = (int)Math.pow(2, (start+8)-cidr);
				int subnets = 256/addresses;
				int gw = Integer.parseInt(item);

				for( int i=0; i<subnets; i++ ) {
					int base = i*addresses;
					int top = ((i+1)*addresses);

					if( gw >= base && gw < top ) {
						network.append(String.valueOf(base));
						break;
					}
				}
			}
			start += 8;
			if( start < 32 ) {
				if ((network.charAt(network.length()-1) == ('.'))){
					network.append("0");
				}
				network.append(".");
			}
		}
		if ((network.charAt(network.length()-1) == ('.'))){
			network.append("0");
		}
		network.append("/");
		network.append(String.valueOf(cidr));
		return network.toString();
	}

	private VLAN toVlan(ApiContext<VCloudApi> ctx, OrgNetwork network) throws CloudException {
		if( network == null ) {
			return null;
		}
		Org org = provider.getOrg(network.getOrg().getHref());
		VLAN vlan = new VLAN();

		vlan.setProviderOwnerId(org.getName());
		vlan.setProviderRegionId(provider.getContext().getRegionId());
		vlan.setProviderVlanId(provider.toId(ctx, network.getHref()));
		vlan.setName(network.getName());
		if( vlan.getName() == null ) {
			vlan.setName(vlan.getProviderVlanId());
		}
		vlan.setDescription(network.getDescription());
		if( vlan.getDescription() == null ) {
			vlan.setDescription(vlan.getName());
		}
		IpScope scope = network.getConfiguration().getIpScope();

		if( scope != null ) {
			String netmask = scope.getNetmask();
			String gateway = scope.getGateway();

			if( netmask != null && gateway != null ) {
				vlan.setCidr(toCidr(gateway, netmask));
			}
			vlan.setGateway(gateway);
			if( scope.getDns2() == null ) {
				if( scope.getDns1() == null ) {
					vlan.setDnsServers(new String[0]);            
				}
				else {
					vlan.setDnsServers(new String[] { scope.getDns1() });
				}
			}
			else if( scope.getDns1() == null ) {
				vlan.setDnsServers(new String[] { scope.getDns2() });
			}
			else {
				vlan.setDnsServers(new String[] { scope.getDns1(), scope.getDns2() });
			}
		}
		else {
			vlan.setDnsServers(new String[0]);            
		}
		return vlan;
	}

	@Override
	public boolean allowsNewSubnetCreation() throws CloudException, InternalException {
		return false;
	}

	@Override
	public Subnet createSubnet(String arg0, String arg1, String arg2, String arg3) throws CloudException, InternalException {
		throw new OperationNotSupportedException();
	}

	@Override
	public VLAN createVlan(String arg0, String arg1, String arg2, String arg3, String[] arg4, String[] arg5) throws CloudException, InternalException {
		throw new OperationNotSupportedException();
	}

	@Override
	public String getProviderTermForNetworkInterface(Locale locale) {
		return "network interface";
	}

	@Override
	public String getProviderTermForSubnet(Locale locale) {
		return "subnet";
	}

	@Override
	public String getProviderTermForVlan(Locale locale) {
		return "network";
	}

	@Override
	public Subnet getSubnet(String subnetId) throws CloudException, InternalException {
		return null;
	}

	@Override
	public boolean isVlanDataCenterConstrained() throws CloudException, InternalException {
		return false;
	}

	@Override
	public Iterable<Subnet> listSubnets(String networkId) throws CloudException, InternalException {
		return Collections.emptyList();
	}

	@Override
	public void removeSubnet(String subnetId) throws CloudException, InternalException {
		throw new OperationNotSupportedException("Subnets not supported with the vCloud API");
	}

	@Override
	public boolean isSubnetDataCenterConstrained() throws CloudException, InternalException {
		return false;
	}

	@Override
	public boolean supportsVlansWithSubnets() throws CloudException, InternalException {
		return false;
	}
}
