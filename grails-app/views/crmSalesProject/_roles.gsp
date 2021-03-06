<%@ page import="org.apache.commons.lang.StringUtils" %>
<table id="role-list" class="table table-striped">
    <thead>
    <tr>
        <th><g:message code="crmSalesProjectRole.type.label" default="Role"/></th>
        <th><g:message code="crmContact.name.label" default="Name"/></th>
        <th><g:message code="crmContact.address.label" default="Address"/></th>
        <th><g:message code="crmContact.telephone.label" default="Telephone"/></th>
        <th><g:message code="crmContact.email.label" default="Email"/></th>
    </tr>
    </thead>
    <tbody>

    <g:each in="${list}" status="i" var="role">
        <g:set var="crmContact" value="${role.contact}"/>
        <tr>

            <td>
                <a class="crm-edit" data-crm-id="${role.id}" href="#" title="Click to edit role details">
                    ${fieldValue(bean: role, field: "type")}
                </a>
                <g:if test="${role.description}">
                    <i class="icon-comment" title="${StringUtils.abbreviate(role.description, 80).encodeAsHTML()}"></i>
                </g:if>
            </td>

            <td>
                <g:link mapping="crm-contact-show" id="${crmContact.id}">
                    ${fieldValue(bean: crmContact, field: "name")}
                </g:link>
            </td>

            <td>${fieldValue(bean: crmContact, field: "address")}</td>

            <td><a href="tel:${fieldValue(bean: crmContact, field: "telephone").replaceAll(/\W/, '')}">${fieldValue(bean: crmContact, field: "telephone")}</a>
            </td>

            <td><a href="mailto:${fieldValue(bean: crmContact, field: "email")}"><g:decorate
                    max="20">${fieldValue(bean: crmContact, field: "email")}</g:decorate></a></td>

        </tr>
    </g:each>
    </tbody>
</table>
