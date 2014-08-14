package grails.plugins.crm.sales

import grails.converters.JSON
import grails.plugins.crm.contact.CrmContact
import grails.plugins.crm.core.DateUtils
import grails.plugins.crm.core.TenantUtils
import grails.plugins.crm.core.WebUtils

import javax.servlet.http.HttpServletResponse
import java.util.concurrent.TimeoutException

/**
 * Main Sales Management controller.
 */
class CrmSalesProjectController {

    static allowedMethods = [list: ['GET', 'POST'], create: ['GET', 'POST'], edit: ['GET', 'POST'], delete: 'POST']

    def crmSecurityService
    def crmSalesService
    def crmContactService
    def selectionService
    def userTagService

    def index() {
        // If any query parameters are specified in the URL, let them override the last query stored in session.
        def cmd = new CrmSalesProjectQueryCommand()
        def query = params.getSelectionQuery()
        bindData(cmd, query ?: WebUtils.getTenantData(request, 'crmSalesProjectQuery'))
        [cmd: cmd]
    }

    def list() {
        def baseURI = new URI('bean://crmSalesService/list')
        def query = params.getSelectionQuery()
        def uri

        switch (request.method) {
            case 'GET':
                uri = params.getSelectionURI() ?: selectionService.addQuery(baseURI, query)
                break
            case 'POST':
                uri = selectionService.addQuery(baseURI, query)
                WebUtils.setTenantData(request, 'crmSalesProjectQuery', query)
                break
        }

        params.max = Math.min(params.max ? params.int('max') : 10, 100)

        def result
        try {
            result = selectionService.select(uri, params)
            if (result.totalCount == 1 && params.view != 'list') {
                redirect action: "show", params: selectionService.createSelectionParameters(uri) + [id: result.head().ident()]
            } else {
                [crmSalesProjectList: result, crmSalesProjectTotal: result.totalCount, selection: uri]
            }
        } catch (Exception e) {
            flash.error = e.message
            [crmSalesProjectList: [], crmSalesProjectTotal: 0, selection: uri]
        }
    }

    def clearQuery() {
        WebUtils.setTenantData(request, 'crmSalesProjectQuery', null)
        redirect(action: "index")
    }

    private void bindDate(def target, String property, String value, TimeZone timezone = null) {
        if (value) {
            try {
                target[property] = DateUtils.parseSqlDate(value, timezone)
            } catch (Exception e) {
                def entityName = message(code: 'crmSalesProject.label', default: 'Sales Project')
                def propertyName = message(code: 'crmSalesProject.' + property + '.label', default: property)
                target.errors.rejectValue(property, 'default.invalid.date.message', [propertyName, entityName, value.toString(), e.message].toArray(), "Invalid date: {2}")
            }
        } else {
            target[property] = null
        }
    }

    def show() {
        def crmSalesProject = CrmSalesProject.findByIdAndTenantId(params.id, TenantUtils.tenant)
        if (!crmSalesProject) {
            flash.error = message(code: 'crmSalesProject.not.found.message', args: [message(code: 'crmSalesProject.label', default: 'Sales Project'), params.id])
            redirect action: 'list'
            return
        }
        def metadata = [statusList: crmSalesService.listSalesProjectStatus(null)]
        [crmSalesProject: crmSalesProject, customer: crmSalesProject.customer, contact: crmSalesProject.contact,
         metadata       : metadata, roles: crmSalesProject.roles.sort{it.type.orderIndex}, selection: params.getSelectionURI()]
    }

    def create() {
        def crmTenant = crmSecurityService.getCurrentTenant()
        def tenant = crmTenant.id
        def currentUser = crmSecurityService.getUserInfo()
        def crmSalesProject = new CrmSalesProject()
        if (!params.currency) {
            params.currency = crmTenant.getOption('currency') ?: (grailsApplication.config.crm.currency.default ?: 'EUR')
        }
        if (params.probability == null) {
            params.probability = formatNumber(number: 0.2, maxFractionDigits: 1)
        }
        if (!params.date1) {
            params.date1 = formatDate(type: 'date', date: new Date())
        }
        def metadata = [:]
        metadata.statusList = CrmSalesProjectStatus.findAllByEnabledAndTenantId(true, tenant)
        metadata.userList = crmSecurityService.getTenantUsers()
        metadata.probabilityList = [0, 0.2, 0.4, 0.6, 0.8, 1]

        switch (request.method) {
            case 'GET':
                bindDate(crmSalesProject, 'date1', params.remove('date1'), currentUser?.timezone)
                bindDate(crmSalesProject, 'date2', params.remove('date2'), currentUser?.timezone)
                bindDate(crmSalesProject, 'date3', params.remove('date3'), currentUser?.timezone)
                bindDate(crmSalesProject, 'date4', params.remove('date4'), currentUser?.timezone)
                bindData(crmSalesProject, params) // TODO SECURITY use white list!
                crmSalesProject.tenantId = tenant
                if (!crmSalesProject.username) {
                    crmSalesProject.username = currentUser?.username
                }
                return [crmSalesProject: crmSalesProject, metadata: metadata, user: currentUser]
            case 'POST':
                def ok = true
                CrmSalesProject.withTransaction { tx ->
                    fixCustomer(params)
                    bindDate(crmSalesProject, 'date1', params.remove('date1'), currentUser?.timezone)
                    bindDate(crmSalesProject, 'date2', params.remove('date2'), currentUser?.timezone)
                    bindDate(crmSalesProject, 'date3', params.remove('date3'), currentUser?.timezone)
                    bindDate(crmSalesProject, 'date4', params.remove('date4'), currentUser?.timezone)
                    bindData(crmSalesProject, params) // TODO SECURITY use white list!
                    if (!crmSalesProject.save(flush: true)) {
                        ok = false
                        tx.setRollbackOnly()
                    }
                }

                println ">>>>> $ok ${crmSalesProject.hasErrors()}"

                if (ok) {
                    event(for: "crmSalesProject", topic: "created", fork: false, data: [id: crmSalesProject.id, tenant: crmSalesProject.tenantId, user: currentUser?.username])
                    flash.success = message(code: 'crmSalesProject.created.message', args: [message(code: 'crmSalesProject.label', default: 'Sales Opportunity'), crmSalesProject.toString()])
                    redirect action: 'show', id: crmSalesProject.id
                } else {
                    def user = crmSecurityService.getUserInfo(params.username ?: crmSalesProject.username)
                    render view: 'create', model: [crmSalesProject: crmSalesProject, metadata: metadata, user: user]
                }
                break
        }
    }

    def edit() {
        def crmTenant = crmSecurityService.getCurrentTenant()
        def tenant = crmTenant.id
        def currentUser = crmSecurityService.getUserInfo()
        def crmSalesProject = CrmSalesProject.findByIdAndTenantId(params.id, crmTenant.id)
        if (!crmSalesProject) {
            flash.error = message(code: 'crmSalesProject.not.found.message', args: [message(code: 'crmSalesProject.label', default: 'Sales Opportunity'), params.id])
            redirect action: 'index'
            return
        }
        def metadata = [:]
        metadata.statusList = CrmSalesProjectStatus.findAllByEnabledAndTenantId(true, tenant)
        metadata.userList = crmSecurityService.getTenantUsers()
        metadata.probabilityList = [0, 0.2, 0.4, 0.6, 0.8, 1]
        switch (request.method) {
            case 'GET':
                return [crmSalesProject: crmSalesProject, metadata: metadata, user: currentUser]
            case 'POST':
                def ok = true
                CrmSalesProject.withTransaction { tx ->
                    if (!params.currency) {
                        params.currency = crmTenant.getOption('currency') ?: (grailsApplication.config.crm.currency.default ?: 'EUR')
                    }
                    fixCustomer(params)
                    bindDate(crmSalesProject, 'date1', params.remove('date1'), currentUser?.timezone)
                    bindDate(crmSalesProject, 'date2', params.remove('date2'), currentUser?.timezone)
                    bindDate(crmSalesProject, 'date3', params.remove('date3'), currentUser?.timezone)
                    bindDate(crmSalesProject, 'date4', params.remove('date4'), currentUser?.timezone)
                    bindData(crmSalesProject, params)
                    if (!crmSalesProject.save(flush: true)) {
                        ok = false
                        tx.setRollbackOnly()
                    }
                }

                if (ok) {
                    event(for: "crmSalesProject", topic: "updated", fork: false, data: [id: crmSalesProject.id, tenant: crmSalesProject.tenantId, user: currentUser?.username])
                    flash.success = message(code: 'crmSalesProject.updated.message', args: [message(code: 'crmSalesProject.label', default: 'Sales Opportunity'), crmSalesProject.toString()])
                    redirect action: 'show', id: crmSalesProject.id
                } else {
                    def user = crmSecurityService.getUserInfo(params.username ?: crmSalesProject.username)
                    render view: 'edit', model: [crmSalesProject: crmSalesProject, metadata: metadata, user: user]
                }
                break
        }
    }

    private List fixCustomer(Map params) {
        def company = params['customer.id'] ? CrmContact.get(params['customer.id']) : null
        def contact = params['contact.id'] ? CrmContact.get(params['contact.id']) : null

        // Company is not specified but the selected person is associated with a company (parent)
        // Set params as if the user had selected the person's parent in the company field.
        if (company == null && contact?.parent != null) {
            company = contact.parent
            params['customer.name'] = company.name
            params['customer.id'] = company.id
        }

        // A company name is specified but it's not an existing company.
        // Create a new company.
        if (params['customer.name'] && !company) {
            company = crmContactService.createCompany(name: params['customer.name']).save(failOnError: true, flush: true)
            params['customer.id'] = company.id
        }

        // A person name is specified but it's not an existing person.
        // Create a new person.
        if (params['contact.name'] && !contact) {
            contact = crmContactService.createPerson([firstName: params['contact.name'], parent: company]).save(failOnError: true, flush: true)
            params['contact.id'] = contact.id
        }

        return [company, contact]
    }

    def addRole(Long id, String type, String description) {
        def crmSalesProject = crmSalesService.getSalesProject(id)
        if (!crmSalesProject) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        if (request.post) {
            def related = params.related
            def relatedContact
            if (related?.isNumber()) {
                relatedContact = crmContactService.getContact(Long.valueOf(related))
                if (!relatedContact) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND)
                    return
                }
                if (relatedContact.tenantId != crmSalesProject.tenantId) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN)
                    return
                }
            } else if (related) {
                if (related.startsWith('@')) {
                    relatedContact = crmContactService.createPerson(firstName: related[1..-1], true)
                } else if (related.endsWith('@')) {
                    relatedContact = crmContactService.createPerson(firstName: related[0..-2], true)
                } else {
                    relatedContact = crmContactService.createCompany(name: related, true)
                }
            }

            if (relatedContact) {
                def roleInstance = crmSalesService.addRole(crmSalesProject, relatedContact, type, description)
                flash.success = "${roleInstance} skapad"
            } else {
                flash.warning = "No role created"
            }
            redirect(action: 'show', id: id, fragment: "roles")
        } else {
            def role = new CrmSalesProjectRole(project: crmSalesProject)
            render template: 'addRole', model: [bean     : role, crmSalesProject: crmSalesProject,
                                                roleTypes: crmSalesService.listSalesProjectRoleType(null)]
        }
    }

    def editRole(Long id, Long r) {
        def crmSalesProject = crmSalesService.getSalesProject(id)
        def roleInstance = CrmSalesProjectRole.get(r)
        if (!(roleInstance && crmSalesProject)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        if (roleInstance.projectId != crmSalesProject.id) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        if (request.post) {
            println params
            CrmSalesProjectRole.withTransaction {
                bindData(roleInstance, params, [include: ['type', 'description']])
                roleInstance.save(flush:true)
            }
            redirect(action: 'show', id: id, fragment: "roles")
        } else {
            render template: 'editRole', model: [crmSalesProject: crmSalesProject, bean: roleInstance,
                                                 roleTypes      : crmSalesService.listSalesProjectRoleType(null)]
        }
    }

    def deleteRole(Long id, Long r) {
        def crmSalesProject = crmSalesService.getSalesProject(id)
        def roleInstance = CrmSalesProjectRole.get(r)
        if (!(roleInstance && crmSalesProject)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        if (roleInstance.projectId != crmSalesProject.id) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        def tombstone = roleInstance.toString()
        def msg = message(code: 'crmSalesProjectRole.deleted.message', default: 'Role {1} deleted', args: ['Role', tombstone])

        roleInstance.delete(flush: true)

        flash.warning = msg

        if (request.xhr) {
            def result = [id: id, r: r, message: msg, role: tombstone]
            render result as JSON
        } else {
            redirect(action: 'show', id: id, fragment: "roles")
        }
    }

    def autocompleteUsername() {
        def query = params.q?.toLowerCase()
        def list = crmSecurityService.getTenantUsers().findAll { user ->
            if (query) {
                return user.name.toLowerCase().contains(query) || user.username.toLowerCase().contains(query)
            }
            return true
        }.collect { user ->
            [id: user.username, text: user.name]
        }
        def result = [q: params.q, timestamp: System.currentTimeMillis(), length: list.size(), more: false, results: list]
        WebUtils.defaultCache(response)
        render result as JSON
    }

    def createFavorite(Long id) {
        def crmSalesProject = crmSalesService.getSalesProject(id)
        if (!crmSalesProject) {
            flash.error = message(code: 'crmSalesProject.not.found.message', args: [message(code: 'crmSalesProject.label', default: 'Opportunity'), id])
            redirect action: 'index'
            return
        }
        userTagService.tag(crmSalesProject, grailsApplication.config.crm.tag.favorite, crmSecurityService.currentUser?.username, TenantUtils.tenant)

        redirect(action: 'show', id: params.id)
    }

    def deleteFavorite(Long id) {
        def crmSalesProject = crmSalesService.getSalesProject(id)
        if (!crmSalesProject) {
            flash.error = message(code: 'crmSalesProject.not.found.message', args: [message(code: 'crmSalesProject.label', default: 'Opportunity'), id])
            redirect action: 'index'
            return
        }
        userTagService.untag(crmSalesProject, grailsApplication.config.crm.tag.favorite, crmSecurityService.currentUser?.username, TenantUtils.tenant)
        redirect(action: 'show', id: params.id)
    }

    def export() {
        def user = crmSecurityService.getUserInfo()
        def namespace = params.namespace ?: 'crmSalesProject'
        if (request.post) {
            def filename = message(code: 'crmSalesProject.label', default: 'Sales Opportunity')
            try {
                def topic = params.topic ?: 'export'
                def result = event(for: namespace, topic: topic,
                        data: params + [user: user, tenant: TenantUtils.tenant, locale: request.locale, filename: filename]).waitFor(60000)?.value
                if (result?.file) {
                    try {
                        WebUtils.inlineHeaders(response, result.contentType, result.filename ?: namespace)
                        WebUtils.renderFile(response, result.file)
                    } finally {
                        result.file.delete()
                    }
                    return null // Success
                } else {
                    flash.warning = message(code: 'crmSalesProject.export.nothing.message', default: 'Nothing was exported')
                }
            } catch (TimeoutException te) {
                flash.error = message(code: 'crmSalesProject.export.timeout.message', default: 'Export did not complete')
            } catch (Exception e) {
                log.error("Export event throwed an exception", e)
                flash.error = message(code: 'crmSalesProject.export.error.message', default: 'Export failed due to an error', args: [e.message])
            }
            redirect(action: "index")
        } else {
            def uri = params.getSelectionURI()
            def layouts = event(for: namespace, topic: (params.topic ?: 'exportLayout'),
                    data: [tenant: TenantUtils.tenant, username: user.username, uri: uri, locale: request.locale]).waitFor(10000)?.values?.flatten()
            [layouts: layouts, selection: uri]
        }
    }
}