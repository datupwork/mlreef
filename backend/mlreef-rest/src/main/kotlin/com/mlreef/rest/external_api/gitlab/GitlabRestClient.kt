package com.mlreef.rest.external_api.gitlab

import com.fasterxml.jackson.databind.ObjectMapper
import com.mlreef.rest.ApplicationConfiguration
import com.mlreef.rest.config.censor
import com.mlreef.rest.exceptions.*
import com.mlreef.rest.external_api.gitlab.dto.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.stereotype.Component
import org.springframework.web.client.*
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


inline fun <reified T : Any> typeRef(): ParameterizedTypeReference<T> = object : ParameterizedTypeReference<T>() {}

@Component
class GitlabRestClient(
    private val conf: ApplicationConfiguration,
    private val builder: RestTemplateBuilder,
) {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Suppress("LeakingThis")
    val gitlabServiceRootUrl = "${conf.gitlab.rootUrl}/api/v4"

    @Suppress("LeakingThis")
    val gitlabOAuthUrl = "${conf.gitlab.rootUrl}/oauth/"

    val gitlabDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    val oAuthAdminToken: AtomicReference<Pair<Long, String>?> = AtomicReference(null)

    private val isAdminTokenOAuth: AtomicBoolean = AtomicBoolean(false)

    companion object {
        val log = LoggerFactory.getLogger(GitlabRestClient::class.java)

        private const val OAUTH_TOKEN_HEADER = "Authorization"
        private const val PERMANENT_TOKEN_HEADER = "PRIVATE-TOKEN"
        private const val OAUTH_TOKEN_VALUE_PREFIX = "Bearer "
        private const val PERMANENT_TOKEN_VALUE_PREFIX = ""
        private const val IS_ADMIN_INTERNAL_HEADER = "is-admin"

        private const val REPEAT_REQUESTS_WHEN_GITLAB_UNAVAILABLE = 2
        private const val PAUSE_BETWEEN_REPEAT_WHEN_GITLAB_UNAVAILABLE_MS = 1000L
        private const val GITLAB_FAILED_LIMIT_REACHED_ERROR_MESSAGE_EMPTY = "\"limit_reached\":[]"
        private const val GITLAB_FAILED_LIMIT_REACHED_ERROR_PREFIX = "\"limit_reached\":["
    }

    fun restTemplate(builder: RestTemplateBuilder): RestTemplate = builder.build()

    fun createProject(
        token: String,
        slug: String,
        name: String,
        description: String,
        visibility: String,
        defaultBranch: String,
        nameSpaceId: Long? = null,
        initializeWithReadme: Boolean = false,
        autoDevopsEnabled: Boolean = false,
        buildTimeout: Int = 18000,
    ): GitlabProject =
        GitlabCreateProjectRequest(
            name = name,
            path = slug,
            description = description,
            visibility = visibility,
            namespaceId = nameSpaceId,
            defaultBranch = defaultBranch,
            ciConfigPath = ".mlreef.yml",
            initializeWithReadme = initializeWithReadme,
            autoDevopsEnabled = autoDevopsEnabled,
            buildTimeout = buildTimeout
        )
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .addErrorDescription(
                409,
                ErrorCode.GitlabProjectAlreadyExists,
                "The project name is already in use by another project in the same namespace"
            )
            .addErrorDescription(
                400,
                ErrorCode.GitlabProjectAlreadyExists,
                "The project name is already in use by another project in the same namespace"
            )
            .addErrorDescription(ErrorCode.GitlabProjectCreationFailed, "Cannot create project $name in gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabProject::class.java)
            }
            ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")

    // https://docs.gitlab.com/ee/api/projects.html#fork-project
    fun forkProject(
        token: String,
        sourceId: Long,
        targetName: String? = null,
        targetPath: String? = null,
        namespaceId: Long? = null,
        namespacePath: String? = null,
    ): GitlabProject =
        GitlabForkProjectRequest(name = targetName, path = targetPath, namespaceId = namespaceId, namespacePath = namespacePath)
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .addErrorDescription(409, ErrorCode.GitlabProjectAlreadyExists, "The project name is already in use by another project in the same namespace")
            .addErrorDescription(400, ErrorCode.GitlabProjectAlreadyExists, "The project name is already in use by another project in the same namespace")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$sourceId/fork"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabProject::class.java)
            }
            ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")


    // https://docs.gitlab.com/ee/api/projects.html#star-a-project
    fun userStarProject(token: String, projectId: Long): GitlabProject {
        return GitlabHttpEntity(null, createUserHeaders(token))
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot star the project $projectId")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/star"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabProject::class.java)
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }

    // https://docs.gitlab.com/ee/api/projects.html#star-a-project
    fun userUnstarProject(token: String, projectId: Long): GitlabProject {
        return GitlabHttpEntity(null, createUserHeaders(token))
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot unstar the project $projectId")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/unstar"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabProject::class.java)
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }

    fun adminGetProjects(search: String? = null): List<GitlabProject> {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(ErrorCode.NotFound, "Cannot find projects")
            .makeRequest {
                val url = if (search != null) {
                    "$gitlabServiceRootUrl/projects?search=$search"
                } else {
                    "$gitlabServiceRootUrl/projects"
                }
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabProject>>())
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }

    fun adminGetUserProjects(userId: Long): List<GitlabProject> {
        val result = ArrayList<GitlabProject>()
        var projectsList = this.adminGetUserProjectsPaged(userId)

        while (projectsList.page <= projectsList.totalPages) {
            result.addAll(projectsList.content ?: listOf())

            if (!(projectsList.page < projectsList.totalPages)) break

            projectsList = this.adminGetUserProjectsPaged(userId, pageNumber = projectsList.page + 1)
        }

        return result
    }

    // https://docs.gitlab.com/ee/api/projects.html#list-user-projects
    // GET /users/:user_id/projects
    fun adminGetUserProjectsPaged(userId: Long, pageSize: Int = 100, pageNumber: Int = 1): GitlabPage<GitlabProject> {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(ErrorCode.NotFound, "Cannot find projects")
            .makeRequest {
                val params = "per_page=$pageSize&page=$pageNumber"

                val url = "$gitlabServiceRootUrl/users/$userId/projects?$params"

                val result = restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabProject>>())

                ResponseEntity.status(result.statusCodeValue).body(GitlabPage(result))
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }

    fun adminGetProject(projectId: Long): GitlabProject {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.GitlabProjectNotExists, "Cannot find project $projectId in gitlab")
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot find project $projectId")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabProject::class.java)
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }

    // https://docs.gitlab.com/ee/api/repositories.html#list-repository-tree
    fun adminGetProjectTree(
        projectId: Long,
        path: String? = null,
        pageSize: Int = 100,
        pageNumber: Int = 1,
        branch: String? = null
    ): RepositoryTreePaged {
        return GitlabHttpEntity(null, createAdminHeaders())
            .addErrorDescription(404, ErrorCode.NotFound, "Cannot find project with id $projectId")
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot fetch repository for project $projectId")
            .makeRequest {
                val params =
                    "per_page=$pageSize&page=$pageNumber${path?.let { "&path=$it" } ?: ""}${branch?.let { "&ref=$it" } ?: ""}"

                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/tree?${params}"

                val result = restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<RepositoryTree>>())

                ResponseEntity.status(result.statusCodeValue).body(
                    RepositoryTreePaged(
                        result.body,
                        result.headers.getOrEmpty("X-Page").getOrNull(0)?.toInt() ?: 0,
                        result.headers.getOrEmpty("X-Total-Pages").getOrNull(0)?.toInt() ?: 0,
                        result.headers.getOrEmpty("X-Total").getOrNull(0)?.toInt() ?: 0,
                        result.headers.getOrEmpty("X-Per-Page").getOrNull(0)?.toInt() ?: 0,
                    )
                )
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }

    //https://docs.gitlab.com/ee/api/repositories.html#get-a-blob-from-repository
    fun adminGetRepositoryFileContent(projectId: Long, sha: String): RepositoryFile {
        return GitlabHttpEntity(null, createAdminHeaders())
            .addErrorDescription(404, ErrorCode.NotFound, "Cannot find project with id $projectId")
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot fetch repository for project $projectId")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/blobs/$sha"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, RepositoryFile::class.java)
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }

    // https://docs.gitlab.com/ee/api/repository_files.html#get-file-from-repository
    fun adminGetRepositoryFileContentAndInformation(
        projectId: Long,
        path: String,
        ref: String = "master"
    ): RepositoryFileFullInfo {
        return GitlabHttpEntity(null, createAdminHeaders())
            .addErrorDescription(404, ErrorCode.NotFound, "Cannot find project with id $projectId")
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot fetch repository for project $projectId")
            .makeRequest {
                val encodedFilePath = URLEncoder.encode(path, StandardCharsets.UTF_8.name())
                val encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8.name())
                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/files/$encodedFilePath?ref=$encodedRef"
                restTemplate(builder).exchange(URI(url), HttpMethod.GET, it, RepositoryFileFullInfo::class.java)
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }


    fun adminGetProjectMembers(projectId: Long): List<GitlabUserInProject> {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.GitlabProjectNotExists, "Cannot find project $projectId in gitlab")
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot get users for project $projectId")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members/all"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabUserInProject>>())
            } ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
    }

    fun adminGetProjectMember(projectId: Long, userId: Long): GitlabUserInProject {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.GitlabProjectNotExists, "Cannot find project $projectId in gitlab")
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot get users for project $projectId")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members/$userId"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabUserInProject::class.java)
            }
    }

    fun adminAddUserToProject(
        projectId: Long,
        userId: Long,
        accessLevel: GitlabAccessLevel = GitlabAccessLevel.DEVELOPER,
        expiresAt: Instant? = null
    ): GitlabUserInProject {
        val expiresDate = if (expiresAt != null) gitlabDateTimeFormatter.format(expiresAt) else null
        return GitlabAddUserToProjectRequest(userId, accessLevel.accessCode, expiresDate)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                404,
                ErrorCode.UserNotExisting,
                "Cannot add user to project. The project or user doesn't exist"
            )
            .addErrorDescription(
                409,
                ErrorCode.UserAlreadyExisting,
                "Cannot add user to project. User already is in the project"
            )
            .addErrorDescription(ErrorCode.GitlabUserAddingToGroupFailed, "Cannot add user to the project")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabUserInProject::class.java)
            }
    }

    fun adminAddGroupToProject(
        projectId: Long,
        groupId: Long,
        accessLevel: GitlabAccessLevel = GitlabAccessLevel.GUEST,
        expiresAt: Instant? = null
    ): GitlabGroupInProject {
        val expiresDate = if (expiresAt != null) gitlabDateTimeFormatter.format(expiresAt) else null
        return GitlabAddGroupToProjectRequest(groupId, accessLevel.accessCode, expiresDate)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                404,
                ErrorCode.UserNotExisting,
                "Cannot add group to project. The project or user doesn't exist"
            )
            .addErrorDescription(
                409,
                ErrorCode.UserAlreadyExisting,
                "Cannot add user to project. User already is in the project"
            )
            .addErrorDescription(ErrorCode.GitlabUserAddingToGroupFailed, "Cannot add user to the project")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/share"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabGroupInProject::class.java)
            }
    }

    fun adminEditUserInProject(
        projectId: Long,
        userId: Long,
        accessLevel: GitlabAccessLevel = GitlabAccessLevel.DEVELOPER,
        expiresAt: Instant? = null
    ): GitlabUserInProject {
        val expiresDate = if (expiresAt != null) gitlabDateTimeFormatter.format(expiresAt) else null
        return GitlabAddUserToProjectRequest(userId, accessLevel.accessCode, expiresDate)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                404,
                ErrorCode.UserNotExisting,
                "Cannot add user to project. The project or user doesn't exist"
            )
            .addErrorDescription(
                409,
                ErrorCode.UserAlreadyExisting,
                "Cannot add user to project. User already is in the project"
            )
            .addErrorDescription(ErrorCode.GitlabUserAddingToGroupFailed, "Cannot add user to the project")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members/$userId"
                restTemplate(builder).exchange(url, HttpMethod.PUT, it, GitlabUserInProject::class.java)
            }
    }

    fun userEditUserInProject(
        token: String,
        projectId: Long,
        userId: Long,
        accessLevel: GitlabAccessLevel = GitlabAccessLevel.DEVELOPER
    ): GitlabUserInProject {
        return GitlabAddUserToProjectRequest(userId, accessLevel.accessCode)
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .addErrorDescription(
                404,
                ErrorCode.UserNotExisting,
                "Cannot add user to project. The project or user doesn't exist"
            )
            .addErrorDescription(
                409,
                ErrorCode.UserAlreadyExisting,
                "Cannot add user to project. User already is in the project"
            )
            .addErrorDescription(ErrorCode.GitlabUserAddingToGroupFailed, "Cannot add user to the project")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members/$userId"
                restTemplate(builder).exchange(url, HttpMethod.PUT, it, GitlabUserInProject::class.java)
            }
    }

    @Deprecated("unused?")
    fun userDeleteUserFromProject(token: String, projectId: Long, userId: Long) {
        GitlabHttpEntity(null, createUserHeaders(token))
            .addErrorDescription(
                ErrorCode.GitlabMembershipDeleteFailed,
                "Cannot revoke user's membership from project $projectId"
            )
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members/$userId"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }

    fun adminDeleteUserFromProject(projectId: Long, userId: Long) {
        GitlabHttpEntity(null, createAdminHeaders())
            .addErrorDescription(
                ErrorCode.GitlabMembershipDeleteFailed,
                "Cannot revoke user's $userId membership from project $projectId"
            )
            .makeRequestNoBody {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members/$userId"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }

    fun adminDeleteGroupFromProject(projectId: Long, groupId: Long) {
        GitlabHttpEntity(null, createAdminHeaders())
            .addErrorDescription(
                404,
                ErrorCode.GitlabMembershipDeleteFailed,
                "Group $groupId is not member of project $projectId"
            )
            .addErrorDescription(
                ErrorCode.GitlabMembershipDeleteFailed,
                "Cannot revoke group's $groupId membership from project $projectId"
            )
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/share/$groupId"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }

    fun userGetUserInProject(token: String, projectId: Long, userId: Long): GitlabUserInProject {
        return GitlabHttpEntity<String>("", createUserHeaders(token))
            .addErrorDescription(
                404,
                ErrorCode.UserNotExisting,
                "Cannot find user in project. User or project does not exist"
            )
            .addErrorDescription(
                ErrorCode.GitlabCommonError,
                "Cannot find user in project. User or project does not exist"
            )
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members/$userId"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabUserInProject::class.java)
            }
    }

    fun adminGetUsersInProjects(projectId: Long, searchNameEmail: String): List<GitlabUserInProject> {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.GitlabUserNotExisting, "Cannot find project")
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Cannot search user in project")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/users?search=$searchNameEmail"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabUserInProject>>())
            }
    }

    fun adminGetUserInProject(projectId: Long, userId: Long): GitlabUserInProject {
        return GitlabHttpEntity<String>("", createAdminHeaders())
            .addErrorDescription(
                404,
                ErrorCode.UserNotExisting,
                "Cannot find user in project. User or project does not exist"
            )
            .addErrorDescription(
                ErrorCode.GitlabCommonError,
                "Cannot find user in project. User or project does not exist"
            )
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/members/$userId"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabUserInProject::class.java)
            }
    }

    fun userUpdateProject(
        id: Long,
        token: String,
        name: String? = null,
        description: String? = null,
        visibility: String? = null
    ): GitlabProject {
        return GitlabUpdateProjectRequest(name = name, description = description, visibility = visibility)
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .addErrorDescription(ErrorCode.GitlabProjectUpdateFailed, "Cannot update project with $id in gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$id"
                restTemplate(builder).exchange(url, HttpMethod.PUT, it, GitlabProject::class.java)
            }
    }

    @Deprecated("unused?")
    fun adminUpdateProject(id: Long, name: String, description: String, visibility: String): GitlabProject {
        return GitlabUpdateProjectRequest(name = name)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(ErrorCode.GitlabProjectUpdateFailed, "Cannot update project with $id in gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$id"
                restTemplate(builder).exchange(url, HttpMethod.PUT, it, GitlabProject::class.java)
            }
    }

    fun deleteProject(id: Long, token: String) {
        GitlabHttpEntity(null, createUserHeaders(token))
            .addErrorDescription(ErrorCode.GitlabProjectDeleteFailed, "Cannot delete project with id $id in gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$id"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }


    fun createBranch(token: String, projectId: Long, targetBranch: String, sourceBranch: String): Branch {
        return GitlabCreateBranchRequest(branch = targetBranch, ref = sourceBranch)
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .addErrorDescription(
                409,
                ErrorCode.GitlabBranchCreationFailed,
                "Cannot create branch $targetBranch in project with id $projectId. Branch exists"
            )
            .addErrorDescription(
                ErrorCode.GitlabBranchCreationFailed,
                "Cannot create branch $sourceBranch -> $targetBranch in project with id $projectId"
            )
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/branches"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, Branch::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/branches.html#get-single-repository-branch
    fun getBranch(token: String, projectId: Long, branch: String): Branch {
        return GitlabHttpEntity("", createUserHeaders(token))
            .addErrorDescription(404, ErrorCode.GitlabBranchNotExists, "Cannot get branch $branch in project with id $projectId.")
            .makeRequest {
                val encodedBranch = URLEncoder.encode(branch, StandardCharsets.UTF_8.name())
                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/branches/$encodedBranch"
                restTemplate(builder).exchange(URI(url), HttpMethod.GET, it, Branch::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/branches.html#get-single-repository-branch
    fun adminGetBranch(projectId: Long, branch: String): Branch {
        return GitlabHttpEntity("", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.GitlabBranchNotExists, "Cannot get branch $branch in project with id $projectId.")
            .makeRequest {
                val encodedBranch = URLEncoder.encode(branch, StandardCharsets.UTF_8.name())
                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/branches/$encodedBranch"
                restTemplate(builder).exchange(URI(url), HttpMethod.GET, it, Branch::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/branches.html#list-repository-branches
    //GET /projects/:id/repository/branches/:branch
    fun getBranches(token: String, projectId: Long): List<Branch> {
        return GitlabHttpEntity("", createUserHeaders(token))
            .addErrorDescription(404, ErrorCode.GitlabProjectNotExists, "Cannot get branches of project with id $projectId.")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/branches"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<Branch>>())
            }
    }

    //https://docs.gitlab.com/ee/api/branches.html#delete-repository-branch
    fun deleteBranch(token: String, projectId: Long, targetBranch: String) {
        GitlabHttpEntity(null, createUserHeaders(token))
            .addErrorDescription(ErrorCode.GitlabBranchDeletionFailed, "Cannot delete branch $targetBranch in project with id $projectId")
            .makeRequestNoBody {
                val encodedBranch = URLEncoder.encode(targetBranch, StandardCharsets.UTF_8.name())
                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/branches/$encodedBranch"
                restTemplate(builder).exchange(URI(url), HttpMethod.DELETE, it, Any::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/branches.html#delete-repository-branch
    fun adminDeleteBranch(projectId: Long, targetBranch: String) {
        GitlabHttpEntity(null, createAdminHeaders())
            .addErrorDescription(ErrorCode.GitlabBranchDeletionFailed, "Cannot delete branch $targetBranch in project with id $projectId")
            .makeRequestNoBody {
                val encodedBranch = URLEncoder.encode(targetBranch, StandardCharsets.UTF_8.name())
                val url = "$gitlabServiceRootUrl/projects/$projectId/repository/branches/$encodedBranch"
                restTemplate(builder).exchange(URI(url), HttpMethod.DELETE, it, Any::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/commits.html#create-a-commit-with-multiple-files-and-actions
    fun commitFiles(
        token: String,
        projectId: Long,
        targetBranch: String,
        commitMessage: String,
        fileContents: Map<String, String?>,
        action: GitlabCommitOperations,
        force: Boolean = false,
        isBase64: Boolean = false,
    ): Commit =
        GitlabCommitRequest(
            branch = targetBranch,
            actions = fileContents.map {
                GitlabCommitAction(
                    filePath = it.key,
                    content = if (action != GitlabCommitOperations.MOVE) it.value else null,
                    previousPath = if (action == GitlabCommitOperations.MOVE) it.value else null,
                    action = action.name.toLowerCase(),
                    encoding = if (isBase64) "base64" else "text",
                )
            },
            commitMessage = commitMessage,
            force = force
        ).let {
            GitlabHttpEntity(it, createUserHeaders(token))
        }.addErrorDescription(error = ErrorCode.GitlabCommitFailed, name = "Cannot commit ${fileContents.keys.joinToString()} in $targetBranch for Gitlab project $projectId").makeRequest {
            val url = "$gitlabServiceRootUrl/projects/$projectId/repository/commits"
            restTemplate(builder).exchange(url, HttpMethod.POST, it, Commit::class.java)
        }

    fun adminCommitFiles(
        projectId: Long,
        targetBranch: String,
        commitMessage: String,
        fileContents: Map<String, String?>,
        action: GitlabCommitOperations,
        force: Boolean = false,
        isBase64: Boolean = false,
    ): Commit =
        GitlabCommitRequest(
            branch = targetBranch,
            actions = fileContents.map {
                GitlabCommitAction(
                    filePath = it.key,
                    content = it.value,
                    action = action.name.toLowerCase(),
                    encoding = if (isBase64) "base64" else "text",
                )
            },
            commitMessage = commitMessage,
            force = force
        ).let {
            GitlabHttpEntity(it, createAdminHeaders())
        }.addErrorDescription(
            error = ErrorCode.GitlabCommitFailed,
            name = "Cannot commit ${fileContents.keys.joinToString()} in $targetBranch for Gitlab project $projectId"
        ).makeRequest {
            val url = "$gitlabServiceRootUrl/projects/$projectId/repository/commits"
            restTemplate(builder).exchange(url, HttpMethod.POST, it, Commit::class.java)
        }


    //https://docs.gitlab.com/ee/api/pipelines.html#create-a-new-pipeline
    fun createPipeline(
        token: String,
        projectId: Long,
        commitRef: String,
        variables: List<GitlabVariable> = listOf()
    ): GitlabPipeline {
        return GitlabCreatePipelineRequest(
            ref = commitRef,
            variables = variables
        )
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .addErrorDescription(ErrorCode.GitlabCommitFailed, "Cannot start pipeline for commit  $commitRef")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipeline"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabPipeline::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/pipelines.html#create-a-new-pipeline
    fun adminCreatePipeline(projectId: Long, commitRef: String, variables: List<GitlabVariable> = listOf()): GitlabPipeline {
        return GitlabCreatePipelineRequest(ref = commitRef, variables = variables)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(ErrorCode.GitlabCommitFailed, "Cannot start pipeline for commit  $commitRef")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipeline"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabPipeline::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/pipelines.html#cancel-a-pipelines-jobs
    fun cancelPipeline(token: String, projectId: Long, pipelineId: Long): GitlabPipeline {
        return GitlabHttpEntity("", createUserHeaders(token))
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipelines/$pipelineId/cancel"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabPipeline::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/pipelines.html#cancel-a-pipelines-jobs
    fun adminCancelPipeline(projectId: Long, pipelineId: Long): GitlabPipeline {
        return GitlabHttpEntity("", createAdminHeaders())
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipelines/$pipelineId/cancel"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabPipeline::class.java)
            }
    }


    //https://docs.gitlab.com/ee/api/pipelines.html#cancel-a-pipelines-jobs
    fun deletePipeline(token: String, projectId: Long, pipelineId: Long) {
        GitlabHttpEntity("", createUserHeaders(token))
            .makeRequestNoBody {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipelines/$pipelineId"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/pipelines.html#cancel-a-pipelines-jobs
    fun adminDeletePipeline(projectId: Long, pipelineId: Long) {
        GitlabHttpEntity("", createAdminHeaders())
            .makeRequestNoBody {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipelines/$pipelineId"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/pipelines.html#get-a-single-pipeline
    // GET /projects/:id/pipelines/:pipeline_id
    fun getPipeline(token: String, projectId: Long, pipelineId: Long): GitlabPipeline {
        return GitlabHttpEntity<String>("", createUserHeaders(token))
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipelines/$pipelineId"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabPipeline::class.java)
            }
    }

    //https://docs.gitlab.com/ee/api/pipelines.html#get-a-single-pipeline
    // GET /projects/:id/pipelines/:pipeline_id
    fun adminGetPipeline(projectId: Long, pipelineId: Long): GitlabPipeline {
        return GitlabHttpEntity<String>("", createAdminHeaders())
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipelines/$pipelineId"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabPipeline::class.java)
            }
    }

    // GET /projects/:id/pipelines/
    fun userGetPipelines(token: String, projectId: Long): List<GitlabPipeline> {
        return GitlabHttpEntity<String>("", createUserHeaders(token))
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipelines"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabPipeline>>())
            }
    }

    // GET /projects/:id/pipelines/
    fun adminGetPipelines(projectId: Long, ref: String? = null, sha: String? = null): List<GitlabPipeline> {
        return GitlabHttpEntity<String>("", createAdminHeaders())
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .makeRequest {
                var params = ref?.let { "ref=$it" } ?: ""
                params = params + sha?.let { (if (params.isNotBlank()) "&" else "") + "sha=$sha" }

                val url =
                    "$gitlabServiceRootUrl/projects/$projectId/pipelines" + if (params.isNotBlank()) "?$params" else ""
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabPipeline>>())
            }
    }

    // GET /projects/:id/pipelines/:pipeline_id/variables
    fun getPipelineVariables(token: String, projectId: Long, pipelineId: Long): List<GitlabVariable> {
        return GitlabHttpEntity<String>("", createUserHeaders(token))
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/pipelines/$pipelineId/variables"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabVariable>>())
            }
    }

    fun getUser(token: String): GitlabUser {
        return GitlabHttpEntity<String>("body", createUserHeaders(token))
            .addErrorDescription(
                404,
                ErrorCode.GitlabUserNotExisting,
                "Cannot find user by token as user. User does not exist"
            )
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Cannot find user by token as user")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/user"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabUser::class.java)
            }
    }

    // https://docs.gitlab.com/ee/api/projects.html#list-user-projects
    fun adminGetUserOwnProjects(userId: Long): List<GitlabProject> {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.GitlabUserNotExisting, "Cannot find user by tid. User does not exist")
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Unable to get users projects")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/users/$userId/projects"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabProject>>())
            }
    }

    // https://docs.gitlab.com/ee/api/projects.html#list-all-projects
    fun userGetUserAllProjects(token: String): List<GitlabProject> {
        val result = ArrayList<GitlabProject>()
        var projectsList = this.userGetUserAllProjectsPaged(token)

        while (projectsList.page <= projectsList.totalPages) {
            result.addAll(projectsList.content ?: listOf())

            if (!(projectsList.page < projectsList.totalPages)) break

            projectsList = this.userGetUserAllProjectsPaged(token, pageNumber = projectsList.page + 1)
        }

        return result
    }

    // https://docs.gitlab.com/ee/api/projects.html#list-all-projects
    fun userGetUserAllProjectsPaged(
        token: String,
        pageSize: Int = 100,
        pageNumber: Int = 1
    ): GitlabPage<GitlabProject> {
        return GitlabHttpEntity<String>("body", createUserHeaders(token))
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Unable to get users projects")
            .makeRequest {
                val params = "membership=true&per_page=$pageSize&page=$pageNumber"

                val url = "$gitlabServiceRootUrl/projects?$params"

                val result = restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabProject>>())

                ResponseEntity.status(result.statusCodeValue).body(GitlabPage(result))
            }
    }

    // https://docs.gitlab.com/ee/api/projects.html#list-all-projects
    fun unauthenticatedGetAllPublicProjects(): List<GitlabProjectSimplified> {
        return GitlabHttpEntity<String>("body", createEmptyHeaders())
            .addErrorDescription(ErrorCode.GitlabCommonError, "Unable to get projects list")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects?simple=true"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabProjectSimplified>>())
            }
    }

    // https://docs.gitlab.com/ee/api/namespaces.html#list-namespaces
    fun getNamespaces(token: String): List<GitlabNamespace> {
        return GitlabHttpEntity<String>(null, createUserHeaders(token))
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot get namespaces list from Gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/namespaces"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabNamespace>>())
            }
    }

    // https://docs.gitlab.com/ee/api/namespaces.html#list-namespaces
    fun getNamespaceById(token: String, id: Long): GitlabNamespace {
        return GitlabHttpEntity<String>(null, createUserHeaders(token))
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot get namespace $id from Gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/namespaces/$id"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabNamespace::class.java)
            }
    }

    // https://docs.gitlab.com/ee/api/namespaces.html#search-for-namespace
    fun findNamespace(token: String, name: String): List<GitlabNamespace> {
        return GitlabHttpEntity<String>("body", createUserHeaders(token))
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot find namespace with name $name")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/namespaces?search=$name"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabNamespace>>())
            }
    }

    fun adminGetUsers(searchNameEmail: String? = null, username: String? = null): List<GitlabUser> {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(
                404,
                ErrorCode.GitlabUserNotExisting,
                "Cannot find user by token as admin. User does not exist"
            )
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Cannot find user by token as admin")
            .makeRequest {
                val url = if (username != null) {
                    "$gitlabServiceRootUrl/users?username=$username"
                } else if (searchNameEmail != null) {
                    "$gitlabServiceRootUrl/users?search=$searchNameEmail"
                } else {
                    "$gitlabServiceRootUrl/users"
                }
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabUser>>())
            }
    }

    fun adminGetExactUser(email: String? = null, username: String? = null): GitlabUser? {
        return this.adminGetUsers(email, username).find { gitlabUser ->
            if (email != null) gitlabUser.email.equals(email, true)
            else if (username != null) gitlabUser.username.equals(username, true)
            else false
        }
    }


    fun adminGetUserById(id: Long): GitlabUser {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.GitlabUserNotExisting, "Cannot find user by id as admin. User does not exist")
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Cannot find user by token as admin")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/users/$id"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabUser::class.java)
            }
    }

    //############################ REGISTRY

    fun adminGetRepositoriesList(projectId: Long): List<GitlabRegistry> {
        return GitlabHttpEntity<String>(null, createAdminHeaders())
            .addErrorDescription(ErrorCode.CannotGetRegistriesListForProject, "Cannot get registries list for project $projectId")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/registry/repositories"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabRegistry>>())
            }
    }

    fun adminDeleteTagFromRepository(projectId: Long, repositoryId: Long, tagName: String) {
        GitlabHttpEntity<String>(null, createAdminHeaders())
            .addErrorDescription(ErrorCode.CannotDeleteTagFromRegistry, "Cannot delete tag $tagName for project $projectId")
            .makeRequestNoBody {
                val url = "$gitlabServiceRootUrl/projects/$projectId/registry/repositories/$repositoryId/tags/$tagName"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }


    //############################ INTERNAL OPERATIONS

    fun assertConnection(): String? {
        log.info("HEALTH-CHECK: GITLAB_ROOT_URL is set to ${conf.gitlab.rootUrl.censor()}")
        log.info("HEALTH-CHECK: GITLAB_ADMIN_TOKEN is set to ${conf.gitlab.adminUserToken.censor()}")
        if (conf.gitlab.rootUrl.isBlank()) {
            throw Error("FATAL: GITLAB_ROOT_URL is empty: ${conf.gitlab.rootUrl}")
        }
        if (conf.gitlab.adminUserToken.isBlank()) {
            throw Error("FATAL: GITLAB_ADMIN_TOKEN is empty: $conf.gitlab.adminUserToken")
        }
        try {
            val adminGetUsers = adminGetUsers()
            val returnInfo = "SUCCESS: Found ${adminGetUsers.size} users on connected Gitlab"
            log.info(returnInfo)
            return returnInfo
        } catch (e: ResourceAccessException) {
            logFatal(e)
            val returnInfo = "WARNING: Gitlab is not available currently! CHECK GITLAB_ROOT_URL or just wait ..."
            log.error(returnInfo, e)
            return returnInfo
        } catch (e: GitlabCommonException) {
            logFatal(e)
            if (e.statusCode == 403) {
                throw Error(
                    "FATAL: Provided GITLAB_ADMIN_TOKEN is not allowed: ${conf.gitlab.adminUserToken.censor()}",
                    e
                )
            }
        } catch (e: HttpServerErrorException) {
            logFatal(e)
            val returnInfo = "WARNING: Gitlab is not working correctly, fix this: ${e.message}"
            log.error(returnInfo, e)
            return returnInfo
        } catch (e: Exception) {
            val returnInfo = "WARNING: Another error during gitlab assertConnection: ${e.message}"
            log.error(returnInfo, e)
            return returnInfo
        }
        return null
    }

    fun userLoginOAuthToGitlab(userName: String, password: String): OAuthToken {
        return GitlabLoginOAuthTokenRequest(grantType = "password", username = userName, password = password)
            .let { GitlabHttpEntity(it, createEmptyHeaders()) }
            .addErrorDescription(401, ErrorCode.UserBadCredentials, "Username or password is incorrect")
            .makeRequest {
                val url = "$gitlabOAuthUrl/token"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, OAuthToken::class.java)
            }
    }

    fun userCheckOAuthTokenInGitlab(accessToken: String): OAuthTokenInfo {
        return GitlabOAuthTokenInfoRequest()
            .let { GitlabHttpEntity(it, createOAuthHeaders(accessToken)) }
            .addErrorDescription(401, ErrorCode.UserBadCredentials, "Token is incorrect")
            .makeRequest {
                val url = "$gitlabOAuthUrl/token/info"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, OAuthTokenInfo::class.java)
            }
    }


    fun adminCreateGroup(groupName: String, path: String, visibility: GitlabVisibility): GitlabGroup {
        return GitlabCreateGroupRequest(name = groupName, path = path, visibility = visibility.name.toLowerCase())
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                409,
                ErrorCode.GitlabGroupCreationFailed,
                "Cannot create group $groupName in gitlab as admin. Group already exists"
            )
            .addErrorDescription(ErrorCode.GitlabGroupCreationFailed, "Cannot create group as admin")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/groups"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabGroup::class.java)
            }
    }

    fun userCreateGroup(token: String, groupName: String, path: String, visibility: GitlabVisibility): GitlabGroup {
        return GitlabCreateGroupRequest(name = groupName, path = path, visibility = visibility.name.toLowerCase())
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .addErrorDescription(
                409,
                ErrorCode.GitlabGroupCreationFailed,
                "Cannot create group $groupName in gitlab. Group already exists"
            )
            .addErrorDescription(
                400,
                ErrorCode.GitlabGroupCreationFailed,
                "Cannot create group $groupName. Probably group name is already taken"
            )
            .addErrorDescription(ErrorCode.GitlabGroupCreationFailed, "Cannot create group as user")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/groups"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabGroup::class.java)
            }
    }

    fun adminUpdateGroup(groupId: Long, groupName: String?, path: String?): GitlabGroup {
        return GitlabUpdateGroupRequest(name = groupName, path = path)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                404,
                ErrorCode.GitlabGroupCreationFailed,
                "Cannot update group $groupName in gitlab. Group not exists"
            )
            .addErrorDescription(ErrorCode.GitlabGroupCreationFailed, "Cannot create group")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/groups/$groupId"
                restTemplate(builder).exchange(url, HttpMethod.PUT, it, GitlabGroup::class.java)
            }
    }

    fun adminDeleteGroup(groupId: Long) {
        GitlabHttpEntity(null, createAdminHeaders())
            .addErrorDescription(ErrorCode.GitlabMembershipDeleteFailed, "Cannot remove group $groupId in Gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/groups/$groupId"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }

    @Deprecated("use admin")
    fun userCreateGroupVariable(token: String, groupId: Long, name: String, value: String): GroupVariable {
        return GitlabCreateVariableRequest(key = name, value = value)
            .let { GitlabHttpEntity(it, createUserHeaders(token)) }
            .addErrorDescription(
                409,
                ErrorCode.GitlabVariableCreationFailed,
                "Cannot create group variable as user. Variable already exists"
            )
            .addErrorDescription(ErrorCode.GitlabVariableCreationFailed, "Cannot create group variable as user")
            .makeRequest {
                restTemplate(builder).exchange(
                    "$gitlabServiceRootUrl/groups/$groupId/variables",
                    HttpMethod.POST,
                    it,
                    GroupVariable::class.java
                )
            }
    }

    @Deprecated("unused")
    fun adminCreateGroupVariable(groupId: Long, name: String, value: String): GroupVariable {
        return GitlabCreateVariableRequest(key = name, value = value)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                409,
                ErrorCode.GitlabVariableCreationFailed,
                "Cannot create group variable as user. Variable already exists"
            )
            .addErrorDescription(ErrorCode.GitlabVariableCreationFailed, "Cannot create group variable as user")
            .makeRequest {
                restTemplate(builder).exchange(
                    "$gitlabServiceRootUrl/groups/$groupId/variables",
                    HttpMethod.POST,
                    it,
                    GroupVariable::class.java
                )
            }
    }

    fun adminCreateProjectVariable(projectId: Long, name: String, value: String): GitlabVariable {
        return GitlabCreateVariableRequest(key = name, value = value)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                409,
                ErrorCode.GitlabVariableCreationFailed,
                "Cannot create project variable as user. Variable already exists"
            )
            .addErrorDescription(ErrorCode.GitlabVariableCreationFailed, "Cannot create project variable as user")
            .makeRequest {
                restTemplate(builder).exchange(
                    "$gitlabServiceRootUrl/projects/$projectId/variables",
                    HttpMethod.POST,
                    it,
                    GitlabVariable::class.java
                )
            }
    }

    fun adminGetProjectVariables(projectId: Long): List<GitlabVariable> {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.ProjectNotExisting, "Cannot find project by id.")
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Unable to get variables of project")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/variables"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabVariable>>())
            }
    }

    fun adminGetProjectVariable(projectId: Long, key: String): GitlabVariable {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(404, ErrorCode.ProjectNotExisting, "Cannot find project by id.")
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Unable to get variables of project")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/projects/$projectId/variables/$key"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, GitlabVariable::class.java)
            }
    }

    fun adminCreateUser(email: String, username: String, name: String, password: String): GitlabUser {
        return GitlabCreateUserRequest(email = email, username = username, name = name, password = password)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                409,
                ErrorCode.UserAlreadyExisting,
                "Cannot create user $username in gitlab. User already exists"
            )
            .addErrorDescription(ErrorCode.GitlabUserCreationFailed, "Cannot create user $username in gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/users"
                restTemplate(builder).exchange(url, HttpMethod.POST, it, GitlabUser::class.java)
            }
    }

    fun adminUpdateUser(gitlabUserId: Long, email: String?, username: String?, name: String?): GitlabUser {
        return GitlabUpdateUserRequest(email = email, username = username, name = name)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                409,
                ErrorCode.UserAlreadyExisting,
                "Cannot change username $username in gitlab. User already exists"
            )
            .addErrorDescription(ErrorCode.GitlabUserCreationFailed, "Cannot update user $gitlabUserId in gitlab")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/users/$gitlabUserId"
                restTemplate(builder).exchange(url, HttpMethod.PUT, it, GitlabUser::class.java)
            }
    }

    fun adminResetUserPassword(gitlabUserId: Long, password: String): GitlabUser {
        return GitlabModifyUserRequest(password = password)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(404, ErrorCode.GitlabUserNotExisting, "User with id $gitlabUserId not found")
            .addErrorDescription(
                ErrorCode.GitlabUserModificationFailed,
                "Cannot modify user with id $gitlabUserId in gitlab"
            )
            .makeRequest {
                val url = "$gitlabServiceRootUrl/users/$gitlabUserId"
                restTemplate(builder).exchange(url, HttpMethod.PUT, it, GitlabUser::class.java)
            }
    }


    fun adminGetUserTokens(gitlabUserId: Long): List<GitlabUserToken> {
        return GitlabGetUserTokenRequest()
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(ErrorCode.GitlabCommonError, "Cannot get token for user $gitlabUserId in gitlab")
            .makeRequest {
                restTemplate(builder).exchange(
                    "$gitlabServiceRootUrl/users/$gitlabUserId/impersonation_tokens/",
                    HttpMethod.GET,
                    it,
                    typeRef<List<GitlabUserToken>>()
                )
            }
    }


    fun adminCreateUserToken(gitlabUserId: Long, tokenName: String, expiresAt: Instant? = null): GitlabUserToken {
        return GitlabCreateUserTokenRequest(name = tokenName, expiresAt = expiresAt)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(409, ErrorCode.GitlabUserTokenCreationFailed, "Cannot create token $tokenName for user in gitlab. Token with the name already exists")
            .addErrorDescription(ErrorCode.GitlabUserTokenCreationFailed, "Cannot create token for user in gitlab")
            .makeRequest {
                restTemplate(builder).exchange(
                    "$gitlabServiceRootUrl/users/$gitlabUserId/impersonation_tokens",
                    HttpMethod.POST,
                    it,
                    GitlabUserToken::class.java
                )
            }
    }

    /**
     * Return user's group. To get user's group you need to make request with user's token
     */
    fun userGetUserGroups(token: String): List<GitlabGroup> {
        return GitlabHttpEntity<String>("body", createUserHeaders(token))
            .addErrorDescription(404, ErrorCode.GitlabUserNotExisting, "Cannot find user by id. User does not exist")
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Unable to get user's groups")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/groups"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabGroup>>())
            }
    }

    /**
     * Return group's users
     */
    fun adminGetGroupMembers(groupId: Long): List<GitlabUserInGroup> {
        return GitlabHttpEntity<String>("body", createAdminHeaders())
            .addErrorDescription(
                404,
                ErrorCode.GitlabUserNotExisting,
                "Cannot find group by id. The group does not exist"
            )
            .addErrorDescription(ErrorCode.GitlabUserNotExisting, "Unable to get users of group")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/groups/$groupId/members"
                restTemplate(builder).exchange(url, HttpMethod.GET, it, typeRef<List<GitlabUserInGroup>>())
            }
    }

    fun adminAddUserToGroup(groupId: Long, userId: Long, accessLevel: GitlabAccessLevel? = null): GitlabUserInGroup {
        return GitlabAddUserToGroupRequest(userId, accessLevel?.accessCode ?: GitlabAccessLevel.DEVELOPER.accessCode)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                404,
                ErrorCode.UserNotExisting,
                "Cannot add user to group. Group or user doesn't exist"
            )
            .addErrorDescription(
                409,
                ErrorCode.UserAlreadyExisting,
                "Cannot add user to group. User already is in group"
            )
            .addErrorDescription(ErrorCode.GitlabUserAddingToGroupFailed, "Cannot add user to group")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/groups/$groupId/members"
                restTemplate(builder).exchange(
                    url, HttpMethod.POST, it, GitlabUserInGroup::class.java
                )
            }
    }


    fun adminEditUserInGroup(groupId: Long, userId: Long, accessLevel: GitlabAccessLevel): GitlabUserInGroup {
        return GitlabAddUserToGroupRequest(userId, accessLevel.accessCode)
            .let { GitlabHttpEntity(it, createAdminHeaders()) }
            .addErrorDescription(
                404,
                ErrorCode.UserNotExisting,
                "Cannot add user to group. Group or user doesn't exist"
            )
            .addErrorDescription(
                409,
                ErrorCode.UserAlreadyExisting,
                "Cannot add user to group. User already is in group"
            )
            .addErrorDescription(ErrorCode.GitlabUserAddingToGroupFailed, "Cannot add user to group")
            .makeRequest {
                val url = "$gitlabServiceRootUrl/groups/$groupId/members/$userId"
                restTemplate(builder).exchange(
                    url, HttpMethod.PUT, it, GitlabUserInGroup::class.java
                )
            }
    }


    fun adminDeleteUserFromGroup(groupId: Long, userId: Long) {
        GitlabHttpEntity(null, createAdminHeaders())
            .addErrorDescription(
                ErrorCode.GitlabMembershipDeleteFailed,
                "Cannot revoke user's membership from group $groupId"
            )
            .makeRequestNoBody {
                val url = "$gitlabServiceRootUrl/groups/$groupId/members/$userId"
                restTemplate(builder).exchange(url, HttpMethod.DELETE, it, Any::class.java)
            }
    }

    private fun logGitlabCall(it: ResponseEntity<out Any>) {
        if (it.statusCode.is2xxSuccessful) {
            log.info("Received from gitlab: ${it.statusCode}")
        } else {
            log.warn(
                """
                | --- Received from gitlab: ${it.statusCode}
                | --- ${it.headers.map { "${it.key}: ${it.value}" }.joinToString()}
            """.trimMargin()
            )
        }
    }

    private fun createAdminHeaders(): HttpHeaders = HttpHeaders().apply {
        val token = resolveAdminToken()
        if (isAdminTokenOAuth.get()) {
            set(OAUTH_TOKEN_HEADER, "$OAUTH_TOKEN_VALUE_PREFIX$token")
        } else {
            set(PERMANENT_TOKEN_HEADER, "$PERMANENT_TOKEN_VALUE_PREFIX$token")
        }

        set(IS_ADMIN_INTERNAL_HEADER, "true")
    }

    private fun resolveAdminToken(): String {
        if (!conf.gitlab.adminUserToken.isBlank()) {
            return conf.gitlab.adminUserToken
        } else {
            val oauthToken = oAuthAdminToken.get()

            if (oauthToken != null) return oauthToken.second

            isAdminTokenOAuth.set(true)

            return refreshAdminOAuthToken()
        }
    }

    private fun refreshAdminOAuthToken(): String {
        synchronized(this) {
            if (oAuthAdminToken.get() != null) return oAuthAdminToken.get()!!.second

            return forceRefreshAdminOAuthToken()
        }
    }

    private fun forceRefreshAdminOAuthToken(): String {
        synchronized(this) {
            this.userLoginOAuthToGitlab(conf.gitlab.adminUsername, conf.gitlab.adminPassword)
                .let { oAuthAdminToken.set(Pair(it.createdAt, it.accessToken)) }
            return oAuthAdminToken.get()?.second
                ?: throw GitlabConnectException("Cannot get OAuth token from Gitlab")
        }
    }

    private fun logFatal(e: Exception) {
        log.error("FATAL: MLReef rest service cannot use gitlab instance!", e)
    }

    private fun createUserHeaders(token: String): HttpHeaders = HttpHeaders().apply {
        if (token.length > 25) {
            set(OAUTH_TOKEN_HEADER, "$OAUTH_TOKEN_VALUE_PREFIX$token")
        } else {
            set(PERMANENT_TOKEN_HEADER, "$PERMANENT_TOKEN_VALUE_PREFIX$token")
        }

        set(IS_ADMIN_INTERNAL_HEADER, "false")
    }

    private fun createEmptyHeaders(): HttpHeaders = HttpHeaders()

    private fun createOAuthHeaders(token: String): HttpHeaders = HttpHeaders().apply {
        set(OAUTH_TOKEN_HEADER, "$OAUTH_TOKEN_VALUE_PREFIX$token")
    }

    private inner class GitlabHttpEntity<T>(body: T?, headers: HttpHeaders) : HttpEntity<T>(body, null) {
        private val errorsMap = HashMap<Int?, Pair<ErrorCode?, String?>>()
        private val internalHeaders = HttpHeaders()

        init {
            internalHeaders.putAll(headers)
        }

        fun addErrorDescription(error: ErrorCode?, name: String?): GitlabHttpEntity<T> =
            addErrorDescription(null, error, name)

        fun addErrorDescription(code: Int?, errorCode: ErrorCode?, errorName: String?): GitlabHttpEntity<T> {
            errorsMap[code] = Pair(errorCode, errorName)
            return this
        }

        fun getErrorCode(code: Int?): ErrorCode? =
            errorsMap[code]?.first ?: errorsMap.get(null)?.first

        fun getErrorName(code: Int?): String? =
            errorsMap[code]?.second ?: errorsMap.get(null)?.second

        override fun getHeaders(): HttpHeaders = this.internalHeaders
    }

    private fun <T : GitlabHttpEntity<out Any>, R> T.makeRequest(block: (T) -> ResponseEntity<out R>): R =
        try {
            block.invoke(this)
                .also { logGitlabCall(it) }
                .body
                ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
        } catch (ex: HttpStatusCodeException) {
            processGitlabException(ex, this, block)
                ?: throw Exception("GitlabRestClient: Gitlab response does not contain a body.")
        }

    private fun <T : GitlabHttpEntity<out Any>, R> T.makeRequestNoBody(block: (T) -> ResponseEntity<out R>) =
        try {
            block.invoke(this)
                .also { logGitlabCall(it) }
        } catch (ex: HttpStatusCodeException) {
            processGitlabException(ex, this, block)
        }

    private fun <T : GitlabHttpEntity<out Any>, R> processGitlabException(
        ex: HttpStatusCodeException,
        entity: T,
        block: (T) -> ResponseEntity<out R>
    ): R? {
        return try {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED || ex.statusCode == HttpStatus.FORBIDDEN) {
                repeatUnauthorized(entity, ex, block).body
            } else if (ex.statusCode == HttpStatus.BAD_GATEWAY || ex.statusCode == HttpStatus.INTERNAL_SERVER_ERROR) {
                repeatBadGateway(entity, ex, block).body
            } else if (
                ex.statusCode == HttpStatus.BAD_REQUEST
                && ex.responseBodyAsString.contains(GITLAB_FAILED_LIMIT_REACHED_ERROR_PREFIX)
                && !ex.responseBodyAsString.contains(GITLAB_FAILED_LIMIT_REACHED_ERROR_MESSAGE_EMPTY)
            ) {
                repeatBadGateway(entity, ex, block).body
            } else
                throw handleException(
                    entity.getErrorCode(ex.rawStatusCode),
                    entity.getErrorName(ex.rawStatusCode),
                    ex
                )
        } catch (ex: HttpStatusCodeException) {
            throw handleException(
                entity.getErrorCode(ex.rawStatusCode),
                entity.getErrorName(ex.rawStatusCode),
                ex
            )
        }
    }


    private fun <T : GitlabHttpEntity<out Any>, R> repeatUnauthorized(
        entity: T,
        ex: HttpStatusCodeException,
        block: (T) -> R
    ): R {
        val isAdmin = entity.headers.getFirst(IS_ADMIN_INTERNAL_HEADER)?.toBoolean() ?: false

        if (isAdmin) {
            forceRefreshAdminOAuthToken() //Possible that threads will enter this method twice or more, but it is ok
            val newHeaders = createAdminHeaders()

            if (newHeaders.containsKey(OAUTH_TOKEN_HEADER)) {
                entity.headers.set(OAUTH_TOKEN_HEADER, newHeaders.getFirst(OAUTH_TOKEN_HEADER))
            } else if (newHeaders.containsKey(PERMANENT_TOKEN_HEADER)) {
                entity.headers.set(PERMANENT_TOKEN_HEADER, newHeaders.getFirst(PERMANENT_TOKEN_HEADER))
            } else throw ex

            return block.invoke(entity)

        } else throw ex
    }

    private fun <T : GitlabHttpEntity<out Any>, R> repeatBadGateway(
        entity: T,
        ex: HttpStatusCodeException,
        block: (T) -> R,
        turn: Int = 1
    ): R {
        if (turn > REPEAT_REQUESTS_WHEN_GITLAB_UNAVAILABLE)
            throw ex

        Thread.sleep(PAUSE_BETWEEN_REPEAT_WHEN_GITLAB_UNAVAILABLE_MS * turn)

        return try {
            block.invoke(entity)
        } catch (ex: HttpClientErrorException) {
            repeatBadGateway(entity, ex, block, turn + 1)
        }
    }

    private fun handleException(error: ErrorCode?, message: String?, response: HttpStatusCodeException): RestException {
        log.error("Received error from gitlab: ${response.responseHeaders?.location} ${response.statusCode}")
        log.error(response.responseHeaders?.toString())
        val detailMessage = response.responseBodyAsString
        val statusCode = response.statusCode.value()
        log.error(detailMessage)

        val currentError = error ?: ErrorCode.GitlabCommonError
        val errorName = message ?: "Gitlab common error"

        when (response.statusCode) {
            HttpStatus.BAD_REQUEST -> return BadRequestException(currentError, detailMessage)
            HttpStatus.CONFLICT -> return ConflictException(currentError, detailMessage)
            HttpStatus.BAD_GATEWAY -> return GitlabBadGatewayException(detailMessage)
            HttpStatus.NOT_FOUND -> return NotFoundException(currentError, detailMessage)
            HttpStatus.FORBIDDEN -> return GitlabAuthenticationFailedException(statusCode, currentError, detailMessage)
            else -> return GitlabCommonException(statusCode, currentError, detailMessage)
        }
    }
}
