package org.ligoj.app.plugin.scm.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.MatcherUtil;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.ParameterValueRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.scm.github.client.GitHubContributor;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Test class of {@link GithubPluginResource}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class GithubPluginResourceTest extends AbstractServerTest {
	@Autowired
	private GithubPluginResource resource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	@Autowired
	private ParameterValueRepository parameterValueRepository;
	@Autowired
	private ConfigurationResource configuration;

	protected int subscription;

	@Before
	public void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class[] { Node.class, Parameter.class, Project.class, Subscription.class, ParameterValue.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");
		// Override the API URL pointing to the mock server
		configuration.saveOrUpdate("service:scm:github:api-url", "http://localhost:" + MOCK_PORT + "/");

		// Coverage only
		resource.getKey();
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is
	 * only one subscription for a service.
	 */
	protected Integer getSubscription(final String project) {
		return getSubscription(project, GithubPluginResource.KEY);
	}

	@Test
	public void delete() throws Exception {
		resource.delete(subscription, false);
		em.flush();
		em.clear();
		// No custom data -> nothing to check;
	}

	@Test
	public void getVersion() throws Exception {
		Assert.assertNull(resource.getVersion(subscription));
	}

	@Test
	public void getLastVersion() throws Exception {
		Assert.assertNull(resource.getLastVersion());
	}

	@Test
	public void link() throws Exception {
		prepareMockRepoDetail();
		httpServer.start();

		// Invoke create for an already created entity, since for now, there is
		// nothing but validation pour SonarQube
		resource.link(this.subscription);

		// Nothing to validate for now...
	}

	@Test
	public void linkNotFound() throws Exception {
		thrown.expect(ValidationJsonException.class);
		thrown.expect(MatcherUtil.validationMatcher("service:scm:github:repository", "github-repository"));

		prepareMockUser();
		httpServer.start();

		parameterValueRepository.findAllBySubscription(subscription).stream()
				.filter(v -> v.getParameter().getId().equals(GithubPluginResource.KEY + ":repository")).findFirst().get().setData("0");
		em.flush();
		em.clear();

		// Invoke create for an already created entity, since for now, there is
		// nothing but validation pour SonarQube
		resource.link(this.subscription);

		// Nothing to validate for now...
	}

	@SuppressWarnings("unchecked")
	@Test
	public void checkSubscriptionStatus() throws Exception {
		prepareMockRepoDetail();
		prepareMockContributors();
		final SubscriptionStatusWithData nodeStatusWithData = resource
				.checkSubscriptionStatus(subscriptionResource.getParametersNoCheck(subscription));
		Assert.assertTrue(nodeStatusWithData.getStatus().isUp());
		Assert.assertEquals(3, nodeStatusWithData.getData().get("watchers"));
		Assert.assertEquals(3, nodeStatusWithData.getData().get("stars"));
		Assert.assertEquals(2, nodeStatusWithData.getData().get("issues"));
		final List<GitHubContributor> contribs = (List<GitHubContributor>) nodeStatusWithData.getData().get("contribs");
		Assert.assertEquals(3, contribs.size());
		Assert.assertEquals("fabdouglas", contribs.get(0).getLogin());
		Assert.assertEquals(345, contribs.get(0).getContributions());
		Assert.assertEquals("https://avatars1.githubusercontent.com/u/579170?v=4", contribs.get(0).getAvatarUrl());
	}

	private void prepareMockRepoDetail() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/repos/junit/gfi-gstack"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/scm/github/repo-detail.json").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private void prepareMockContributors() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/repos/junit/gfi-gstack/contributors"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/scm/github/contribs.json").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private void prepareMockUser() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/users/junit")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/scm/github/user.json").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private void prepareMockRepoSearch() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/search/repositories")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/scm/github/search.json").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	@Test
	public void checkStatus() throws Exception {
		prepareMockUser();
		Assert.assertTrue(resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
	}

	@Test
	public void checkStatusBadRequest() throws Exception {
		httpServer.stubFor(get(urlPathEqualTo("/")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.start();
		Assert.assertFalse(resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
	}

	@Test
	public void findReposByName() throws Exception {
		prepareMockRepoSearch();
		httpServer.start();

		final List<NamedBean<String>> projects = resource.findReposByName("service:scm:github:dig", "plugin-");
		Assert.assertEquals(10, projects.size());
		Assert.assertEquals("plugin-storage-owncloud", projects.get(0).getId());
		Assert.assertEquals("plugin-storage-owncloud", projects.get(0).getName());
	}

	@Test
	public void findReposByNameNoListing() throws Exception {
		httpServer.start();

		final List<NamedBean<String>> projects = resource.findReposByName("service:scm:github:dig", "as-");
		Assert.assertEquals(0, projects.size());
	}

}
