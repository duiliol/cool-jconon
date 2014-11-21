package it.cnr.cool.interceptor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import it.cnr.cool.cmis.model.ACLType;
import it.cnr.cool.cmis.service.ACLService;
import it.cnr.cool.cmis.service.CMISService;
import it.cnr.cool.exception.CoolUserFactoryException;
import it.cnr.cool.rest.AccountingImport;
import it.cnr.cool.rest.Proxy;
import it.cnr.cool.security.service.GroupService;
import it.cnr.cool.security.service.impl.alfresco.CMISAuthority;
import it.cnr.cool.util.MimeTypes;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.bindings.impl.SessionImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.commons.httpclient.HttpStatus;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:/META-INF/cool-accounting-test-context.xml" })
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class AccountingACLInterceptorTest {

	@Autowired
	private CMISService cmisService;
	@Autowired
	private ACLService aclService;
	@Autowired
	private GroupService groupService;
	@Autowired
	private AccountingACLInterceptor accountingACLInterceptor;
	@Autowired
	private AccountingImport accountingImport;
	
	@Autowired
	private Proxy proxy;

	private Session cmisSession;
	private SessionImpl cmisBindingSession;
	
	private ObjectId folderContabili; 
	private static final String USERCONTABILI = "mjackson";
	private static final CMISAuthority CONTABILI_AUTHORITY = new CMISAuthority("USER", USERCONTABILI, null, null);
	private static final String URL = "url";
	
	@Before
	public void setUp() {
		cmisSession = cmisService.createAdminSession();
		cmisBindingSession = cmisService.getAdminSession();
		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put(PropertyIds.NAME, "TESTCONTABILI");
		properties.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_FOLDER.value());
		properties.put("sigla_contabili_aspect:codice_proteo", "999");
		properties.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, Collections.singletonList(AccountingACLInterceptor.CONTABILI_ASPECT));
		folderContabili = cmisSession.createFolder(properties, cmisSession.getObjectByPath("/Data Dictionary"));
	}
	
	@Test
	public void testImportContabili() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		HttpSession session = req.getSession();
		session.setAttribute(CMISService.BINDING_SESSION,
				cmisBindingSession);
		
		req.setParameter(URL, "service/api/node/workspace/SpacesStore/" + folderContabili.getId() + "/ruleset/rules");
		req.setContentType(MimeTypes.JSON.mimetype());
		MockHttpServletResponse res = new MockHttpServletResponse();
		try {
			req.setContent(createJSONRule());
			proxy.post(req, res);
		} catch (IOException e) {
			assertNull("Cannot create RULE ", USERCONTABILI);
		}
		accountingImport.execute(this.getClass().getResource("/import").getFile());		
	}
	@Test
	public void testGroupContabili() {
		/**
		 * Cerco il gruppo CONTABILI_BNL
		 */
		String groupContabili = accountingACLInterceptor.getGroupName();
		try {
			groupService.loadGroup(groupContabili, cmisBindingSession);
		} catch (CoolUserFactoryException e) {
			if (e.getStatus() == HttpStatus.SC_NOT_FOUND) {
				groupService.createGroup(groupContabili, groupContabili, cmisBindingSession);
			} else {
				assertNull("Cannot create GROUP ", groupContabili);
			}
		}		
	}
	@Test
	public void testContabili() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		HttpSession session = req.getSession();
		session.setAttribute(CMISService.BINDING_SESSION,
				cmisBindingSession);
		req.setParameter(URL, "service/cnr/nodes/permissions/workspace/SpacesStore/" + folderContabili.getId());
		req.setContentType(MimeTypes.JSON.mimetype());
		MockHttpServletResponse res = new MockHttpServletResponse();
		try {
			req.setContent(createJSON(USERCONTABILI, ACLType.Consumer.name(), false));
			proxy.post(req, res);
		} catch (IOException e) {
			assertNull("Cannot add PERMISSION to ", USERCONTABILI);
		}
		List<CMISAuthority> children = groupService.children(accountingACLInterceptor.getGroupName(), cmisBindingSession);
		assertTrue(children.contains(CONTABILI_AUTHORITY));
		try {
			req.setContent(createJSON(USERCONTABILI, ACLType.Consumer.name(), true));
			proxy.post(req, res);
		} catch (IOException e) {
			assertNull("Cannot add PERMISSION to ", USERCONTABILI);
		}
		children = groupService.children(accountingACLInterceptor.getGroupName(), cmisBindingSession);
		assertFalse(children.contains(CONTABILI_AUTHORITY));
	}	
	
	@After
	public void testCompleted(){
		((Folder)cmisSession.getObject(folderContabili)).deleteTree(true, UnfileObject.DELETE, true);
	}
	
	private byte[] createJSON(String authority, String role, boolean remove) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode objNode = mapper.createObjectNode();
		ArrayNode permissions = objNode.putArray("permissions");
		ObjectNode permission = permissions.insertObject(0);
		permission.put("authority", authority);
		permission.put("role", role);
		if (remove)
			permission.put("remove", remove);
		return objNode.toString().getBytes();
	}
	
	private byte[] createJSONRule() {
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode objNode = mapper.createObjectNode();
		objNode.put("title", "Gestione Mandati BNL");
		objNode.put("description", "Gestione Mandati BNL");
		objNode.put("executeAsynchronously", false);
		objNode.put("applyToChildren", true);
		ArrayNode ruleTypes = objNode.putArray("ruleType");
		ruleTypes.add("inbound");
		
		ObjectNode objNodeAction = objNode.putObject("action");
		objNodeAction.put("actionDefinitionName", "composite-action");
		objNodeAction.put("executeAsync", false);
		ArrayNode actions = objNodeAction.putArray("actions");
		ObjectNode action = actions.insertObject(0);
		action.put("actionDefinitionName", "script");		
		ObjectNode objNodeParameterValues = action.putObject("parameterValues");
		objNodeParameterValues.put("script-ref", cmisSession.getObjectByPath("/Data Dictionary/Scripts/GestioneMandatiBNL.js").
				getProperty("alfcmis:nodeRef").getValueAsString());
		action.put("executeAsync", false);	
		ArrayNode conditions = objNodeAction.putArray("conditions");
		ObjectNode condition = conditions.insertObject(0);
		condition.put("conditionDefinitionName", "compare-text-property");
		condition.put("invertCondition", false);
		ObjectNode conditionParameterValues = condition.putObject("parameterValues");
		conditionParameterValues.put("operation", "BEGINS");
		conditionParameterValues.put("value", "Rendicontazione");
		conditionParameterValues.put("property", "cm:name");
		return objNode.toString().getBytes();
	}
	
}