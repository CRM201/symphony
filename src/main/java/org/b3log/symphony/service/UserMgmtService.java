/*
 * Copyright (c) 2012-2015, b3log.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.symphony.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.inject.Inject;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.time.DateUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.CompositeFilter;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.Filter;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.repository.annotation.Transactional;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Requests;
import org.b3log.latke.util.Sessions;
import org.b3log.latke.util.Strings;
import org.b3log.symphony.model.Article;
import org.b3log.symphony.model.Comment;
import org.b3log.symphony.model.Option;
import org.b3log.symphony.model.Pointtransfer;
import org.b3log.symphony.model.UserExt;
import org.b3log.symphony.repository.ArticleRepository;
import org.b3log.symphony.repository.CommentRepository;
import org.b3log.symphony.repository.OptionRepository;
import org.b3log.symphony.repository.UserRepository;
import org.b3log.symphony.util.Symphonys;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * User management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.6.5.2, Jul 14, 2015
 * @since 0.2.0
 */
@Service
public class UserMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(UserMgmtService.class.getName());

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Pointtransfer management service.
     */
    @Inject
    private PointtransferMgmtService pointtransferMgmtService;

    /**
     * Tries to login with cookie.
     *
     * @param request the specified request
     * @param response the specified response
     * @return returns {@code true} if logged in, returns {@code false} otherwise
     */
    public boolean tryLogInWithCookie(final HttpServletRequest request, final HttpServletResponse response) {
        final Cookie[] cookies = request.getCookies();
        if (null == cookies || 0 == cookies.length) {
            return false;
        }

        try {
            for (final Cookie cookie : cookies) {
                if (!"b3log-latke".equals(cookie.getName())) {
                    continue;
                }

                final JSONObject cookieJSONObject = new JSONObject(cookie.getValue());

                final String userEmail = cookieJSONObject.optString(User.USER_EMAIL);
                if (Strings.isEmptyOrNull(userEmail)) {
                    break;
                }

                final JSONObject user = userRepository.getByEmail(userEmail.toLowerCase().trim());
                if (null == user) {
                    break;
                }

                final String ip = Requests.getRemoteAddr(request);

                if (UserExt.USER_STATUS_C_INVALID == user.optInt(UserExt.USER_STATUS)) {
                    Sessions.logout(request, response);

                    updateOnlineStatus(user.optString(Keys.OBJECT_ID), ip, false);

                    return false;
                }

                final String userPassword = user.optString(User.USER_PASSWORD);
                final String password = cookieJSONObject.optString(User.USER_PASSWORD);
                if (userPassword.equals(password)) {
                    Sessions.login(request, response, user);
                    updateOnlineStatus(user.optString(Keys.OBJECT_ID), ip, true);
                    LOGGER.log(Level.DEBUG, "Logged in with cookie[email={0}]", userEmail);

                    return true;
                }
            }
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Parses cookie failed, clears the cookie[name=b3log-latke]", e);

            final Cookie cookie = new Cookie("b3log-latke", null);
            cookie.setMaxAge(0);
            cookie.setPath("/");

            response.addCookie(cookie);
        }

        return false;
    }

    /**
     * Updates a user's online status and saves the login time and IP.
     *
     * @param userId the specified user id
     * @param ip the specified IP, could be "" if the {@code onlineFlag} is {@code false}
     * @param onlineFlag the specified online flag
     * @throws ServiceException service exception
     */
    public void updateOnlineStatus(final String userId, final String ip, final boolean onlineFlag) throws ServiceException {
        Transaction transaction = null;

        try {
            final JSONObject user = userRepository.get(userId);
            if (null == user) {
                return;
            }

            transaction = userRepository.beginTransaction();

            user.put(UserExt.USER_ONLINE_FLAG, onlineFlag);
            user.put(UserExt.USER_LATEST_LOGIN_TIME, System.currentTimeMillis());

            if (onlineFlag) {
                user.put(UserExt.USER_LATEST_LOGIN_IP, ip);
            }

            userRepository.update(userId, user);

            transaction.commit();
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Updates user online status failed", e);

            if (null != transaction && transaction.isActive()) {
                transaction.rollback();
            }

            throw new ServiceException(e);
        }
    }

    /**
     * Updates a user's profiles by the specified request json object.
     *
     * @param requestJSONObject the specified request json object (user), for example,      <pre>
     * {
     *     "oId": "",
     *     "userURL": "",
     *     "userQQ": "",
     *     "userIntro": "",
     *     "userAvatarType": int,
     *     "userAvatarURL": ""
     * }
     * </pre>
     *
     * @throws ServiceException service exception
     */
    public void updateProfiles(final JSONObject requestJSONObject) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            final String oldUserId = requestJSONObject.optString(Keys.OBJECT_ID);
            final JSONObject oldUser = userRepository.get(oldUserId);

            if (null == oldUser) {
                throw new ServiceException(langPropsService.get("updateFailLabel"));
            }

            // Update
            oldUser.put(User.USER_URL, requestJSONObject.optString(User.USER_URL));
            oldUser.put(UserExt.USER_QQ, requestJSONObject.optString(UserExt.USER_QQ));
            oldUser.put(UserExt.USER_INTRO, requestJSONObject.optString(UserExt.USER_INTRO));
            oldUser.put(UserExt.USER_AVATAR_TYPE, requestJSONObject.optString(UserExt.USER_AVATAR_TYPE));
            oldUser.put(UserExt.USER_AVATAR_URL, requestJSONObject.optString(UserExt.USER_AVATAR_URL));

            userRepository.update(oldUserId, oldUser);
            transaction.commit();
        } catch (final RepositoryException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Updates user profiles failed", e);
            throw new ServiceException(langPropsService.get("updateFailLabel"));
        }
    }

    /**
     * Updates a user's sync B3log settings by the specified request json object.
     *
     * @param requestJSONObject the specified request json object (user), for example,      <pre>
     * {
     *     "oId": "",
     *     "userB3Key": "",
     *     "userB3ClientAddArticleURL": "",
     *     "userB3ClientUpdateArticleURL": "",
     *     "userB3ClientAddCommentURL": "",
     * }
     * </pre>
     *
     * @throws ServiceException service exception
     */
    public void updateSyncB3(final JSONObject requestJSONObject) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            final String oldUserId = requestJSONObject.optString(Keys.OBJECT_ID);
            final JSONObject oldUser = userRepository.get(oldUserId);

            if (null == oldUser) {
                throw new ServiceException(langPropsService.get("updateFailLabel"));
            }

            // Update
            oldUser.put(UserExt.USER_B3_KEY, requestJSONObject.optString(UserExt.USER_B3_KEY));
            oldUser.put(UserExt.USER_B3_CLIENT_ADD_ARTICLE_URL, requestJSONObject.optString(UserExt.USER_B3_CLIENT_ADD_ARTICLE_URL));
            oldUser.put(UserExt.USER_B3_CLIENT_UPDATE_ARTICLE_URL, requestJSONObject.optString(UserExt.USER_B3_CLIENT_UPDATE_ARTICLE_URL));
            oldUser.put(UserExt.USER_B3_CLIENT_ADD_COMMENT_URL, requestJSONObject.optString(UserExt.USER_B3_CLIENT_ADD_COMMENT_URL));

            userRepository.update(oldUserId, oldUser);
            transaction.commit();
        } catch (final RepositoryException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Updates user sync b3log settings failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Updates a user's password by the specified request json object.
     *
     * @param requestJSONObject the specified request json object (user), for example,      <pre>
     * {
     *     "oId": "",
     *     "userPassword": "", // Hashed
     * }
     * </pre>
     *
     * @throws ServiceException service exception
     */
    public void updatePassword(final JSONObject requestJSONObject) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            final String oldUserId = requestJSONObject.optString(Keys.OBJECT_ID);
            final JSONObject oldUser = userRepository.get(oldUserId);

            if (null == oldUser) {
                throw new ServiceException(langPropsService.get("updateFailLabel"));
            }

            // Update
            oldUser.put(User.USER_PASSWORD, requestJSONObject.optString(User.USER_PASSWORD));

            userRepository.update(oldUserId, oldUser);
            transaction.commit();
        } catch (final RepositoryException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Updates user sync b3log settings failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Adds a user with the specified request json object.
     *
     * @param requestJSONObject the specified request json object, for example,      <pre>
     * {
     *     "userName": "",
     *     "userEmail": "",
     *     "userAppRole": int,
     *     "userPassword": "", // Hashed
     *     "userRole": "", // optional, uses {@value Role#DEFAULT_ROLE} instead if not speciffied
     *     "userStatus": int // optional, uses {@value UserExt#USER_STATUS_C_NOT_VERIFIED} instead if not specified
     * }
     * </pre>,see {@link User} for more details
     *
     * @return generated user id
     * @throws ServiceException if user name or email duplicated, or repository exception
     */
    public synchronized String addUser(final JSONObject requestJSONObject) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            final String userEmail = requestJSONObject.optString(User.USER_EMAIL).trim().toLowerCase();
            final String userName = requestJSONObject.optString(User.USER_NAME);
            JSONObject user = userRepository.getByName(userName);
            if (null != user && UserExt.USER_STATUS_C_VALID == user.optInt(UserExt.USER_STATUS)) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }

                throw new ServiceException(langPropsService.get("duplicatedUserNameLabel") + " [" + userName + "]");
            }

            boolean toUpdate = false;
            String ret = null;
            user = userRepository.getByEmail(userEmail);
            if (null != user) {
                if (UserExt.USER_STATUS_C_VALID == user.optInt(UserExt.USER_STATUS)) {
                    if (transaction.isActive()) {
                        transaction.rollback();
                    }

                    throw new ServiceException(langPropsService.get("duplicatedEmailLabel"));
                }

                toUpdate = true;
                ret = user.optString(Keys.OBJECT_ID);
            }

            user = new JSONObject();
            user.put(User.USER_NAME, userName);
            user.put(User.USER_EMAIL, userEmail);
            user.put(UserExt.USER_APP_ROLE, requestJSONObject.optInt(UserExt.USER_APP_ROLE));
            user.put(User.USER_PASSWORD, requestJSONObject.optString(User.USER_PASSWORD));
            user.put(User.USER_ROLE, requestJSONObject.optString(User.USER_ROLE, Role.DEFAULT_ROLE));
            user.put(User.USER_URL, "");
            user.put(UserExt.USER_ARTICLE_COUNT, 0);
            user.put(UserExt.USER_COMMENT_COUNT, 0);
            user.put(UserExt.USER_TAG_COUNT, 0);
            user.put(UserExt.USER_STATUS, 0);
            user.put(UserExt.USER_B3_KEY, "");
            user.put(UserExt.USER_B3_CLIENT_ADD_ARTICLE_URL, "");
            user.put(UserExt.USER_B3_CLIENT_UPDATE_ARTICLE_URL, "");
            user.put(UserExt.USER_B3_CLIENT_ADD_COMMENT_URL, "");
            user.put(UserExt.USER_INTRO, "");
            user.put(UserExt.USER_AVATAR_TYPE, UserExt.USER_AVATAR_TYPE_C_UPLOAD);
            user.put(UserExt.USER_AVATAR_URL, Symphonys.get("defaultThumbnailURL"));
            user.put(UserExt.USER_QQ, "");
            user.put(UserExt.USER_ONLINE_FLAG, false);
            user.put(UserExt.USER_LATEST_ARTICLE_TIME, 0L);
            user.put(UserExt.USER_LATEST_CMT_TIME, 0L);
            user.put(UserExt.USER_LATEST_LOGIN_TIME, 0L);
            user.put(UserExt.USER_LATEST_LOGIN_IP, "");
            user.put(UserExt.USER_CURRENT_CHECKIN_STREAK_START, 0);
            user.put(UserExt.USER_CURRENT_CHECKIN_STREAK_END, 0);
            user.put(UserExt.USER_LONGEST_CHECKIN_STREAK_START, 0);
            user.put(UserExt.USER_LONGEST_CHECKIN_STREAK_END, 0);
            user.put(UserExt.USER_POINT, 0);
            final int status = requestJSONObject.optInt(UserExt.USER_STATUS, UserExt.USER_STATUS_C_NOT_VERIFIED);
            user.put(UserExt.USER_STATUS, status);
            final JSONObject memberCntOption = optionRepository.get(Option.ID_C_STATISTIC_MEMBER_COUNT);
            int memberCount = memberCntOption.optInt(Option.OPTION_VALUE);

            if (toUpdate) {
                user.put(UserExt.USER_NO, memberCount);
                userRepository.update(ret, user);
            } else {
                user.put(UserExt.USER_NO, ++memberCount);

                ret = userRepository.add(user);

                // Updates stat. (member count +1)
                memberCntOption.put(Option.OPTION_VALUE, String.valueOf(memberCount));
                optionRepository.update(Option.ID_C_STATISTIC_MEMBER_COUNT, memberCntOption);
            }

            transaction.commit();

            if (UserExt.USER_STATUS_C_VALID == status) {
                // Point
                pointtransferMgmtService.transfer(Pointtransfer.ID_C_SYS, ret,
                        Pointtransfer.TRANSFER_TYPE_C_INIT, Pointtransfer.TRANSFER_SUM_C_INIT, ret);
            }

            return ret;
        } catch (final RepositoryException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Adds a user failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Removes a user specified by the given user id.
     *
     * @param userId the given user id
     * @throws ServiceException service exception
     */
    public void removeUser(final String userId) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            userRepository.remove(userId);

            transaction.commit();
        } catch (final RepositoryException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Removes a user[id=" + userId + "] failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Updates the specified user by the given user id.
     *
     * @param userId the given user id
     * @param user the specified user
     * @throws ServiceException service exception
     */
    public void updateUser(final String userId, final JSONObject user) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            userRepository.update(userId, user);

            transaction.commit();
        } catch (final RepositoryException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Updates a user[id=" + userId + "] failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Updates the specified user's email by the given user id.
     *
     * @param userId the given user id
     * @param user the specified user, contains the new email
     * @throws ServiceException service exception
     */
    public void updateUserEmail(final String userId, final JSONObject user) throws ServiceException {
        final String newEmail = user.optString(User.USER_EMAIL);

        final Transaction transaction = userRepository.beginTransaction();

        try {
            if (null != userRepository.getByEmail(newEmail)) {
                throw new ServiceException(langPropsService.get("duplicatedEmailLabel") + " [" + newEmail + "]");
            }

            // Update relevent comments of the user
            final Query commentQuery = new Query().setFilter(new PropertyFilter(Comment.COMMENT_AUTHOR_ID, FilterOperator.EQUAL, userId));
            final JSONObject commentResult = commentRepository.get(commentQuery);
            final JSONArray comments = commentResult.optJSONArray(Keys.RESULTS);
            for (int i = 0; i < comments.length(); i++) {
                final JSONObject comment = comments.optJSONObject(i);
                comment.put(Comment.COMMENT_AUTHOR_EMAIL, newEmail);

                commentRepository.update(comment.optString(Keys.OBJECT_ID), comment);
            }

            // Update relevent articles of the user
            final Query articleQuery = new Query().setFilter(new PropertyFilter(Article.ARTICLE_AUTHOR_ID, FilterOperator.EQUAL, userId));
            final JSONObject articleResult = articleRepository.get(articleQuery);
            final JSONArray articles = articleResult.optJSONArray(Keys.RESULTS);
            for (int i = 0; i < articles.length(); i++) {
                final JSONObject article = articles.optJSONObject(i);
                article.put(Article.ARTICLE_AUTHOR_EMAIL, newEmail);

                articleRepository.update(article.optString(Keys.OBJECT_ID), article);
            }

            // Update the user
            userRepository.update(userId, user);

            transaction.commit();
        } catch (final RepositoryException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Updates email of the user[id=" + userId + "] failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Updates the specified user's username by the given user id.
     *
     * @param userId the given user id
     * @param user the specified user, contains the new username
     * @throws ServiceException service exception
     */
    public void updateUserName(final String userId, final JSONObject user) throws ServiceException {
        final String newUserName = user.optString(User.USER_NAME);

        final Transaction transaction = userRepository.beginTransaction();

        try {
            if (null != userRepository.getByName(newUserName)) {
                throw new ServiceException(langPropsService.get("duplicatedUserNameLabel") + " [" + newUserName + "]");
            }

            // Update the user
            userRepository.update(userId, user);

            transaction.commit();
        } catch (final RepositoryException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Updates username of the user[id=" + userId + "] failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Resets unverified users.
     */
    @Transactional
    public void resetUnverifiedUsers() {
        final Date now = new Date();
        final long yesterdayTime = DateUtils.addDays(now, -1).getTime();
        
        final List<Filter> filters = new ArrayList<Filter>();
        filters.add(new PropertyFilter(UserExt.USER_STATUS, FilterOperator.EQUAL, UserExt.USER_STATUS_C_NOT_VERIFIED));
        filters.add(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.LESS_THAN_OR_EQUAL, yesterdayTime));
        filters.add(new PropertyFilter(User.USER_NAME, FilterOperator.NOT_EQUAL, "_"));

        final Query query = new Query().setFilter(new CompositeFilter(CompositeFilterOperator.AND, filters));

        try {
            final JSONObject result = userRepository.get(query);
            final JSONArray users = result.optJSONArray(Keys.RESULTS);

            for (int i = 0; i < users.length(); i++) {
                final JSONObject user = users.optJSONObject(i);
                final String id = user.optString(Keys.OBJECT_ID);
                
                user.put(User.USER_NAME, "_");
                
                userRepository.update(id, user);

                LOGGER.log(Level.INFO, "Reset unverified user [email=" + user.optString(User.USER_EMAIL));
            }
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Reset unverified users failed", e);
        }
    }

}
