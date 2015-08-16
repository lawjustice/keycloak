package org.keycloak.authentication;

import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.ClientConnection;
import org.keycloak.OAuth2Constants;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.login.LoginFormsProvider;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.LoginProtocol;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.services.managers.ClientSessionCode;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.resources.LoginActionsService;
import org.keycloak.util.Time;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class AuthenticationProcessor {
    public static final String CURRENT_AUTHENTICATION_EXECUTION = "current.authentication.execution";
    protected static Logger logger = Logger.getLogger(AuthenticationProcessor.class);
    protected RealmModel realm;
    protected UserSessionModel userSession;
    protected ClientSessionModel clientSession;
    protected ClientConnection connection;
    protected UriInfo uriInfo;
    protected KeycloakSession session;
    protected BruteForceProtector protector;
    protected EventBuilder event;
    protected HttpRequest request;
    protected String flowId;
    protected String flowPath;
    /**
     * This could be an error message forwarded from brokering when the broker failed authentication
     * and we want to continue authentication locally.  forwardedErrorMessage can then be displayed by
     * whatever form is challenging.
     */
    protected String forwardedErrorMessage;
    protected boolean userSessionCreated;


    public RealmModel getRealm() {
        return realm;
    }

    public ClientSessionModel getClientSession() {
        return clientSession;
    }

    public ClientConnection getConnection() {
        return connection;
    }

    public UriInfo getUriInfo() {
        return uriInfo;
    }

    public KeycloakSession getSession() {
        return session;
    }

    public UserSessionModel getUserSession() {
        return userSession;
    }

    public boolean isUserSessionCreated() {
        return userSessionCreated;
    }

    public AuthenticationProcessor setRealm(RealmModel realm) {
        this.realm = realm;
        return this;
    }

    public AuthenticationProcessor setClientSession(ClientSessionModel clientSession) {
        this.clientSession = clientSession;
        return this;
    }

    public AuthenticationProcessor setConnection(ClientConnection connection) {
        this.connection = connection;
        return this;
    }

    public AuthenticationProcessor setUriInfo(UriInfo uriInfo) {
        this.uriInfo = uriInfo;
        return this;
    }

    public AuthenticationProcessor setSession(KeycloakSession session) {
        this.session = session;
        return this;
    }

    public AuthenticationProcessor setProtector(BruteForceProtector protector) {
        this.protector = protector;
        return this;
    }

    public AuthenticationProcessor setEventBuilder(EventBuilder eventBuilder) {
        this.event = eventBuilder;
        return this;
    }

    public AuthenticationProcessor setRequest(HttpRequest request) {
        this.request = request;
        return this;
    }

    public AuthenticationProcessor setFlowId(String flowId) {
        this.flowId = flowId;
        return this;
    }

    /**
     * This is the path segment to append when generating an action URL.
     *
     * @param flowPath
     */
    public AuthenticationProcessor setFlowPath(String flowPath) {
        this.flowPath = flowPath;
        return this;
    }

    public AuthenticationProcessor setForwardedErrorMessage(String forwardedErrorMessage) {
        this.forwardedErrorMessage = forwardedErrorMessage;
        return this;
    }

    public String generateCode() {
        ClientSessionCode accessCode = new ClientSessionCode(getRealm(), getClientSession());
        clientSession.setTimestamp(Time.currentTime());
        return accessCode.getCode();
    }

    public EventBuilder newEvent() {
        this.event = new EventBuilder(realm, session, connection);
        return this.event;
    }

    public EventBuilder getEvent() {
        return event;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public void setAutheticatedUser(UserModel user) {
        UserModel previousUser = clientSession.getAuthenticatedUser();
        if (previousUser != null && !user.getId().equals(previousUser.getId()))
            throw new AuthenticationFlowException(AuthenticationFlowError.USER_CONFLICT);
        validateUser(user);
        getClientSession().setAuthenticatedUser(user);
    }


    public class Result implements AuthenticationFlowContext {
        AuthenticatorConfigModel authenticatorConfig;
        AuthenticationExecutionModel execution;
        Authenticator authenticator;
        FlowStatus status;
        Response challenge;
        AuthenticationFlowError error;
        List<AuthenticationExecutionModel> currentExecutions;

        private Result(AuthenticationExecutionModel execution, Authenticator authenticator, List<AuthenticationExecutionModel> currentExecutions) {
            this.execution = execution;
            this.authenticator = authenticator;
            this.currentExecutions = currentExecutions;
        }

        @Override
        public EventBuilder newEvent() {
            return AuthenticationProcessor.this.newEvent();
        }

        @Override
        public AuthenticationExecutionModel.Requirement getCategoryRequirementFromCurrentFlow(String authenticatorCategory) {
            List<AuthenticationExecutionModel> executions = realm.getAuthenticationExecutions(execution.getParentFlow());
            for (AuthenticationExecutionModel exe : executions) {
                AuthenticatorFactory factory = (AuthenticatorFactory) getSession().getKeycloakSessionFactory().getProviderFactory(Authenticator.class, exe.getAuthenticator());
                if (factory != null && factory.getReferenceCategory().equals(authenticatorCategory)) {
                    return exe.getRequirement();
                }

            }
            return null;
        }

        @Override
        public AuthenticationExecutionModel getExecution() {
            return execution;
        }

        @Override
        public AuthenticatorConfigModel getAuthenticatorConfig() {
            if (execution.getAuthenticatorConfig() == null) return null;
            if (authenticatorConfig != null) return authenticatorConfig;
            authenticatorConfig = realm.getAuthenticatorConfigById(execution.getAuthenticatorConfig());
            return authenticatorConfig;
        }

        public Authenticator getAuthenticator() {
            return authenticator;
        }

        @Override
        public FlowStatus getStatus() {
            return status;
        }

        @Override
        public void success() {
            this.status = FlowStatus.SUCCESS;
        }

        @Override
        public void failure(AuthenticationFlowError error) {
            status = FlowStatus.FAILED;
            this.error = error;

        }

        @Override
        public void challenge(Response challenge) {
            this.status = FlowStatus.CHALLENGE;
            this.challenge = challenge;

        }

        @Override
        public void forceChallenge(Response challenge) {
            this.status = FlowStatus.FORCE_CHALLENGE;
            this.challenge = challenge;

        }

        @Override
        public void failureChallenge(AuthenticationFlowError error, Response challenge) {
            this.error = error;
            this.status = FlowStatus.FAILURE_CHALLENGE;
            this.challenge = challenge;

        }

        @Override
        public void failure(AuthenticationFlowError error, Response challenge) {
            this.error = error;
            this.status = FlowStatus.FAILED;
            this.challenge = challenge;

        }

        @Override
        public void attempted() {
            this.status = FlowStatus.ATTEMPTED;

        }

        @Override
        public UserModel getUser() {
            return getClientSession().getAuthenticatedUser();
        }

        @Override
        public void setUser(UserModel user) {
            setAutheticatedUser(user);
        }

        @Override
        public RealmModel getRealm() {
            return AuthenticationProcessor.this.getRealm();
        }

        @Override
        public ClientSessionModel getClientSession() {
            return AuthenticationProcessor.this.getClientSession();
        }

        @Override
        public ClientConnection getConnection() {
            return AuthenticationProcessor.this.getConnection();
        }

        @Override
        public UriInfo getUriInfo() {
            return AuthenticationProcessor.this.getUriInfo();
        }

        @Override
        public KeycloakSession getSession() {
            return AuthenticationProcessor.this.getSession();
        }

        @Override
        public HttpRequest getHttpRequest() {
            return AuthenticationProcessor.this.request;
        }

        @Override
        public void attachUserSession(UserSessionModel userSession) {
            AuthenticationProcessor.this.userSession = userSession;
        }

        @Override
        public BruteForceProtector getProtector() {
            return AuthenticationProcessor.this.protector;
        }

        @Override
        public EventBuilder getEvent() {
            return AuthenticationProcessor.this.event;
        }

        @Override
        public String getForwardedErrorMessage() {
            return AuthenticationProcessor.this.forwardedErrorMessage;
        }

        @Override
        public String generateAccessCode() {
            return generateCode();
        }


        public Response getChallenge() {
            return challenge;
        }

        @Override
        public AuthenticationFlowError getError() {
            return error;
        }

        @Override
        public LoginFormsProvider form() {
            String accessCode = generateAccessCode();
            URI action = getActionUrl(accessCode);
            LoginFormsProvider provider = getSession().getProvider(LoginFormsProvider.class)
                    .setUser(getUser())
                    .setActionUri(action)
                    .setClientSessionCode(accessCode);
            if (getForwardedErrorMessage() != null) {
                provider.setError(getForwardedErrorMessage());
            }
            return provider;
        }

        @Override
        public URI getActionUrl(String code) {
            return LoginActionsService.loginActionsBaseUrl(getUriInfo())
                    .path(AuthenticationProcessor.this.flowPath)
                    .queryParam(OAuth2Constants.CODE, code)
                    .queryParam("execution", getExecution().getId())
                    .build(getRealm().getName());
        }

        @Override
        public URI getActionUrl() {
            return getActionUrl(generateAccessCode());
        }

        @Override
        public void cancelLogin() {
            getEvent().error(Errors.REJECTED_BY_USER);
            LoginProtocol protocol = getSession().getProvider(LoginProtocol.class, getClientSession().getAuthMethod());
            protocol.setRealm(getRealm())
                    .setHttpHeaders(getHttpRequest().getHttpHeaders())
                    .setUriInfo(getUriInfo());
            Response response = protocol.cancelLogin(getClientSession());
            forceChallenge(response);
        }

        @Override
        public void resetBrowserLogin() {
            this.status = FlowStatus.RESET_BROWSER_LOGIN;
        }
    }

    public void logFailure() {
        if (realm.isBruteForceProtected()) {
            String username = clientSession.getNote(AbstractUsernameFormAuthenticator.ATTEMPTED_USERNAME);
            // todo need to handle non form failures
            if (username == null) {

            } else {
                protector.failedLogin(realm, username, connection);

            }
        }
    }

    public boolean isSuccessful(AuthenticationExecutionModel model) {
        ClientSessionModel.ExecutionStatus status = clientSession.getExecutionStatus().get(model.getId());
        if (status == null) return false;
        return status == ClientSessionModel.ExecutionStatus.SUCCESS;
    }

    public Response handleBrowserException(Exception failure) {
        if (failure instanceof AuthenticationFlowException) {
            AuthenticationFlowException e = (AuthenticationFlowException) failure;
            logger.error("failed authentication: " + e.getError().toString(), e);
            if (e.getError() == AuthenticationFlowError.INVALID_USER) {
                event.error(Errors.USER_NOT_FOUND);
                return ErrorPage.error(session, Messages.INVALID_USER);
            } else if (e.getError() == AuthenticationFlowError.USER_DISABLED) {
                event.error(Errors.USER_DISABLED);
                return ErrorPage.error(session, Messages.ACCOUNT_DISABLED);
            } else if (e.getError() == AuthenticationFlowError.USER_TEMPORARILY_DISABLED) {
                event.error(Errors.USER_TEMPORARILY_DISABLED);
                return ErrorPage.error(session, Messages.ACCOUNT_TEMPORARILY_DISABLED);

            } else if (e.getError() == AuthenticationFlowError.INVALID_CLIENT_SESSION) {
                event.error(Errors.INVALID_CODE);
                return ErrorPage.error(session, Messages.INVALID_CODE);

            } else if (e.getError() == AuthenticationFlowError.EXPIRED_CODE) {
                event.error(Errors.EXPIRED_CODE);
                return ErrorPage.error(session, Messages.EXPIRED_CODE);

            } else if (e.getError() == AuthenticationFlowError.RESET_TO_BROWSER_LOGIN) {
                resetFlow(getClientSession());
                AuthenticationProcessor processor = new AuthenticationProcessor();
                processor.setClientSession(clientSession)
                        .setFlowPath(LoginActionsService.AUTHENTICATE_PATH)
                        .setFlowId(realm.getBrowserFlow().getId())
                        .setConnection(connection)
                        .setEventBuilder(event)
                        .setProtector(protector)
                        .setRealm(realm)
                        .setSession(session)
                        .setUriInfo(uriInfo)
                        .setRequest(request);
                return processor.authenticate();

            } else {
                event.error(Errors.INVALID_USER_CREDENTIALS);
                return ErrorPage.error(session, Messages.INVALID_USER);
            }

        } else {
            logger.error("failed authentication", failure);
            event.error(Errors.INVALID_USER_CREDENTIALS);
            return ErrorPage.error(session, Messages.UNEXPECTED_ERROR_HANDLING_REQUEST);
        }

    }

    public AuthenticationFlow createFlowExecution(String flowId, AuthenticationExecutionModel execution) {
        AuthenticationFlowModel flow = realm.getAuthenticationFlowById(flowId);
        if (flow == null) {
            logger.error("Unknown flow to execute with");
            throw new AuthenticationFlowException(AuthenticationFlowError.INTERNAL_ERROR);
        }
        if (flow.getProviderId() == null || flow.getProviderId().equals(AuthenticationFlow.BASIC_FLOW)) {
            DefaultAuthenticationFlow flowExecution = new DefaultAuthenticationFlow(this, flow);
            return flowExecution;

        } else if (flow.getProviderId().equals(AuthenticationFlow.FORM_FLOW)) {
            FormAuthenticationFlow flowExecution = new FormAuthenticationFlow(this, execution);
            return flowExecution;
        }
        throw new AuthenticationFlowException("Unknown flow provider type", AuthenticationFlowError.INTERNAL_ERROR);
    }

    public Response authenticate() throws AuthenticationFlowException {
        checkClientSession();
        logger.debug("AUTHENTICATE");
        event.client(clientSession.getClient().getClientId())
                .detail(Details.REDIRECT_URI, clientSession.getRedirectUri())
                .detail(Details.AUTH_METHOD, clientSession.getAuthMethod());
        String authType = clientSession.getNote(Details.AUTH_TYPE);
        if (authType != null) {
            event.detail(Details.AUTH_TYPE, authType);
        }
        UserModel authUser = clientSession.getAuthenticatedUser();
        validateUser(authUser);
        AuthenticationFlow authenticationFlow = createFlowExecution(this.flowId, null);
        Response challenge = authenticationFlow.processFlow();
        if (challenge != null) return challenge;
        if (clientSession.getAuthenticatedUser() == null) {
            throw new AuthenticationFlowException(AuthenticationFlowError.UNKNOWN_USER);
        }
        return authenticationComplete();
    }

    public static void resetFlow(ClientSessionModel clientSession) {
        clientSession.setTimestamp(Time.currentTime());
        clientSession.setAuthenticatedUser(null);
        clientSession.clearExecutionStatus();
        clientSession.clearUserSessionNotes();
        clientSession.removeNote(CURRENT_AUTHENTICATION_EXECUTION);
    }

    public Response authenticationAction(String execution) {
        checkClientSession();
        String current = clientSession.getNote(CURRENT_AUTHENTICATION_EXECUTION);
        if (!execution.equals(current)) {
            logger.debug("Current execution does not equal executed execution.  Might be a page refresh");
            logFailure();
            resetFlow(clientSession);
            return authenticate();
        }
        UserModel authUser = clientSession.getAuthenticatedUser();
        validateUser(authUser);
        AuthenticationExecutionModel model = realm.getAuthenticationExecutionById(execution);
        if (model == null) {
            logger.debug("Cannot find execution, reseting flow");
            logFailure();
            resetFlow(clientSession);
            return authenticate();
        }
        event.client(clientSession.getClient().getClientId())
                .detail(Details.REDIRECT_URI, clientSession.getRedirectUri())
                .detail(Details.AUTH_METHOD, clientSession.getAuthMethod());
        String authType = clientSession.getNote(Details.AUTH_TYPE);
        if (authType != null) {
            event.detail(Details.AUTH_TYPE, authType);
        }

        AuthenticationFlow authenticationFlow = createFlowExecution(this.flowId, model);
        Response challenge = authenticationFlow.processAction(execution);
        if (challenge != null) return challenge;
        if (clientSession.getAuthenticatedUser() == null) {
            throw new AuthenticationFlowException(AuthenticationFlowError.UNKNOWN_USER);
        }
        return authenticationComplete();
    }

    public void checkClientSession() {
        ClientSessionCode code = new ClientSessionCode(realm, clientSession);
        String action = ClientSessionModel.Action.AUTHENTICATE.name();
        if (!code.isValidAction(action)) {
            throw new AuthenticationFlowException(AuthenticationFlowError.INVALID_CLIENT_SESSION);
        }
        if (!code.isActionActive(action)) {
            throw new AuthenticationFlowException(AuthenticationFlowError.EXPIRED_CODE);
        }
        clientSession.setTimestamp(Time.currentTime());
    }

    public Response authenticateOnly() throws AuthenticationFlowException {
        checkClientSession();
        event.client(clientSession.getClient().getClientId())
                .detail(Details.REDIRECT_URI, clientSession.getRedirectUri())
                .detail(Details.AUTH_METHOD, clientSession.getAuthMethod());
        String authType = clientSession.getNote(Details.AUTH_TYPE);
        if (authType != null) {
            event.detail(Details.AUTH_TYPE, authType);
        }
        UserModel authUser = clientSession.getAuthenticatedUser();
        validateUser(authUser);
        AuthenticationFlow authenticationFlow = createFlowExecution(this.flowId, null);
        Response challenge = authenticationFlow.processFlow();
        return challenge;
    }

    public Response attachSessionExecutionRequiredActions() {
        attachSession();
        return AuthenticationManager.actionRequired(session, userSession, clientSession, connection, request, uriInfo, event);
    }

    public void attachSession() {
        String username = clientSession.getAuthenticatedUser().getUsername();
        String attemptedUsername = clientSession.getNote(AbstractUsernameFormAuthenticator.ATTEMPTED_USERNAME);
        if (attemptedUsername != null) username = attemptedUsername;
        String rememberMe = clientSession.getNote(Details.REMEMBER_ME);
        boolean remember = rememberMe != null && rememberMe.equalsIgnoreCase("true");
        if (userSession == null) { // if no authenticator attached a usersession
            userSession = session.sessions().createUserSession(realm, clientSession.getAuthenticatedUser(), username, connection.getRemoteAddr(), clientSession.getAuthMethod(), remember, null, null);
            userSession.setState(UserSessionModel.State.LOGGING_IN);
            userSessionCreated = true;
        }
        if (remember) {
            event.detail(Details.REMEMBER_ME, "true");
        }
        TokenManager.attachClientSession(userSession, clientSession);
        event.user(userSession.getUser())
                .detail(Details.USERNAME, username)
                .session(userSession);
    }

    public void evaluateRequiredActionTriggers() {
        AuthenticationManager.evaluateRequiredActionTriggers(session, userSession, clientSession, connection, request, uriInfo, event, realm, clientSession.getAuthenticatedUser());
    }

    public Response finishAuthentication() {
        event.success();
        RealmModel realm = clientSession.getRealm();
        return AuthenticationManager.redirectAfterSuccessfulFlow(session, realm, userSession, clientSession, request, uriInfo, connection);

    }

    public void validateUser(UserModel authenticatedUser) {
        if (authenticatedUser == null) return;
        if (!authenticatedUser.isEnabled()) throw new AuthenticationFlowException(AuthenticationFlowError.USER_DISABLED);
        if (realm.isBruteForceProtected()) {
            if (protector.isTemporarilyDisabled(session, realm, authenticatedUser.getUsername())) {
                throw new AuthenticationFlowException(AuthenticationFlowError.USER_TEMPORARILY_DISABLED);
            }
        }
    }

    protected Response authenticationComplete() {
        attachSession();
        return AuthenticationManager.nextActionAfterAuthentication(session, userSession, clientSession, connection, request, uriInfo, event);

    }

    public AuthenticationProcessor.Result createAuthenticatorContext(AuthenticationExecutionModel model, Authenticator authenticator, List<AuthenticationExecutionModel> executions) {
        return new Result(model, authenticator, executions);
    }


}
