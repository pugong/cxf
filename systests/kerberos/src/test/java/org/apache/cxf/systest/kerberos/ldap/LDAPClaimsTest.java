/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.systest.kerberos.ldap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.rt.security.claims.Claim;
import org.apache.cxf.rt.security.claims.ClaimCollection;
import org.apache.cxf.sts.claims.ClaimTypes;
import org.apache.cxf.sts.claims.ClaimsParameters;
import org.apache.cxf.sts.claims.LdapClaimsHandler;
import org.apache.cxf.sts.claims.ProcessedClaim;
import org.apache.cxf.sts.claims.ProcessedClaimCollection;
import org.apache.cxf.ws.security.sts.provider.STSException;
import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.ApplyLdifFiles;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreateIndex;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.integ.AbstractLdapTestUnit;
import org.apache.directory.server.core.integ.FrameworkRunner;
import org.apache.wss4j.common.principal.CustomTokenPrincipal;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.util.Assert;

@RunWith(FrameworkRunner.class)

//Define the DirectoryService
@CreateDS(name = "LDAPClaimsTest-class",
          enableAccessControl = false,
          allowAnonAccess = false,
          enableChangeLog = true,
          partitions = {
        @CreatePartition(
            name = "example",
            suffix = "dc=example,dc=com",
            indexes = {
                @CreateIndex(attribute = "objectClass"),
                @CreateIndex(attribute = "dc"),
                @CreateIndex(attribute = "ou")
            }
        ) }
    )

@CreateLdapServer(
    transports = {
        @CreateTransport(protocol = "LDAP")
        }
    )

//Inject an file containing entries
@ApplyLdifFiles("ldap.ldif")

public class LDAPClaimsTest extends AbstractLdapTestUnit {

    private static Properties props;
    private static boolean portUpdated;
    
    private ClassPathXmlApplicationContext appContext;
    
    @BeforeClass
    public static void startServers() throws Exception {
        props = new Properties();

        InputStream is = null;
        try {
            is = LDAPClaimsTest.class.getResourceAsStream("/ldap.properties");
            props.load(is);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    @Before
    public void updatePort() throws Exception {
        if (!portUpdated) {
            String basedir = System.getProperty("basedir");
            if (basedir == null) {
                basedir = new File(".").getCanonicalPath();
            }
            
            // Read in ldap.xml and substitute in the correct port
            File f = new File(basedir + "/src/test/resources/ldap.xml");
            
            FileInputStream inputStream = new FileInputStream(f);
            String content = IOUtils.toString(inputStream, "UTF-8");
            inputStream.close();
            content = content.replaceAll("portno", "" + super.getLdapServer().getPort());
            
            File f2 = new File(basedir + "/target/test-classes/ldapport.xml");
            FileOutputStream outputStream = new FileOutputStream(f2);
            IOUtils.write(content, outputStream, "UTF-8");
            outputStream.close();
            
            portUpdated = true;
        }
        
        appContext = new ClassPathXmlApplicationContext("ldapport.xml");
    }

    @org.junit.Test
    public void testRetrieveClaims() throws Exception {
        LdapClaimsHandler claimsHandler = (LdapClaimsHandler)appContext.getBean("testClaimsHandler");

        String user = props.getProperty("claimUser");
        Assert.notNull(user, "Property 'claimUser' not configured");

        ClaimCollection requestedClaims = createRequestClaimCollection();

        List<URI> expectedClaims = new ArrayList<URI>();
        expectedClaims.add(ClaimTypes.FIRSTNAME);
        expectedClaims.add(ClaimTypes.LASTNAME);
        expectedClaims.add(ClaimTypes.EMAILADDRESS);
       
        ClaimsParameters params = new ClaimsParameters();
        params.setPrincipal(new CustomTokenPrincipal(user));
        ProcessedClaimCollection retrievedClaims = 
            claimsHandler.retrieveClaimValues(requestedClaims, params);

        Assert.isTrue(
                      retrievedClaims.size() == expectedClaims.size(), 
                      "Retrieved number of claims [" + retrievedClaims.size() 
                      + "] doesn't match with expected [" + expectedClaims.size() + "]"
        );

        for (ProcessedClaim c : retrievedClaims) {
            if (expectedClaims.contains(c.getClaimType())) {
                expectedClaims.remove(c.getClaimType());
            } else {
                Assert.isTrue(false, "Claim '" + c.getClaimType() + "' not requested");
            }
        }
    }

    @org.junit.Test(expected = STSException.class)
    public void testRetrieveClaimsWithUnsupportedMandatoryClaimType() throws Exception {
        LdapClaimsHandler claimsHandler = (LdapClaimsHandler)appContext.getBean("testClaimsHandler");

        String user = props.getProperty("claimUser");
        Assert.notNull(user, "Property 'claimUser' not configured");

        ClaimCollection requestedClaims = createRequestClaimCollection();
        // add unsupported but mandatory claim
        Claim claim = new Claim();
        claim.setClaimType(ClaimTypes.GENDER);
        claim.setOptional(false);
        requestedClaims.add(claim);

        ClaimsParameters params = new ClaimsParameters();
        params.setPrincipal(new CustomTokenPrincipal(user));
        ProcessedClaimCollection processedClaim = 
            claimsHandler.retrieveClaimValues(requestedClaims, params);
        
        for (Claim requestedClaim : requestedClaims) {
            URI claimType = requestedClaim.getClaimType();
            boolean found = false;
            if (!requestedClaim.isOptional()) {
                for (ProcessedClaim c : processedClaim) {
                    if (c.getClaimType().equals(claimType)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new STSException("Mandatory claim '" + claim.getClaimType() + "' not found");
                }
            }
        }
    }
    
    @org.junit.Test
    public void testRetrieveClaimsWithUnsupportedOptionalClaimType() throws Exception {
        LdapClaimsHandler claimsHandler = (LdapClaimsHandler)appContext.getBean("testClaimsHandler");

        String user = props.getProperty("claimUser");
        Assert.notNull(user, "Property 'claimUser' not configured");

        ClaimCollection requestedClaims = createRequestClaimCollection();
        // add unsupported but optional unsupported claim
        Claim claim = new Claim();
        claim.setClaimType(ClaimTypes.GENDER);
        claim.setOptional(true);
        requestedClaims.add(claim);

        // Gender is not expected to be returned because not supported
        List<URI> expectedClaims = new ArrayList<URI>();
        expectedClaims.add(ClaimTypes.FIRSTNAME);
        expectedClaims.add(ClaimTypes.LASTNAME);
        expectedClaims.add(ClaimTypes.EMAILADDRESS);
        
        ClaimsParameters params = new ClaimsParameters();
        params.setPrincipal(new CustomTokenPrincipal(user));
        ProcessedClaimCollection retrievedClaims = 
            claimsHandler.retrieveClaimValues(requestedClaims, params);

        Assert.isTrue(
                      retrievedClaims.size() == expectedClaims.size(), 
                      "Retrieved number of claims [" + retrievedClaims.size() 
                      + "] doesn't match with expected [" + expectedClaims.size() + "]"
        );

        for (ProcessedClaim c : retrievedClaims) {
            if (expectedClaims.contains(c.getClaimType())) {
                expectedClaims.remove(c.getClaimType());
            } else {
                Assert.isTrue(false, "Claim '" + c.getClaimType() + "' not requested");
            }
        }
    }
    
    @org.junit.Test    
    public void testSupportedClaims() throws Exception {

        Map<String, String> mapping 
            = CastUtils.cast((Map<?, ?>)appContext.getBean("claimsToLdapAttributeMapping"));

        LdapClaimsHandler cHandler = new LdapClaimsHandler();
        cHandler.setClaimsLdapAttributeMapping(mapping);

        List<URI> supportedClaims = cHandler.getSupportedClaimTypes();

        Assert.isTrue(
                      mapping.size() == supportedClaims.size(), 
                      "Supported claims and claims/ldap attribute mapping size different"
        );

        for (String claim : mapping.keySet()) {
            Assert.isTrue(
                          supportedClaims.contains(new URI(claim)), 
                          "Claim '" + claim + "' not listed in supported list"
            );
        }
    }

    private ClaimCollection createRequestClaimCollection() {
        ClaimCollection claims = new ClaimCollection();
        Claim claim = new Claim();
        claim.setClaimType(ClaimTypes.FIRSTNAME);
        claim.setOptional(true);
        claims.add(claim);
        claim = new Claim();
        claim.setClaimType(ClaimTypes.LASTNAME);
        claim.setOptional(true);
        claims.add(claim);
        claim = new Claim();
        claim.setClaimType(ClaimTypes.EMAILADDRESS);
        claim.setOptional(true);
        claims.add(claim);
        return claims;
    }
    
}
