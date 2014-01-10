package io.github.ibuildthecloud.gdapi.request.resource.impl;

import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Collection;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.CollectionImpl;
import io.github.ibuildthecloud.gdapi.model.impl.WrappedResource;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import io.github.ibuildthecloud.gdapi.util.TypeUtils;
import io.github.ibuildthecloud.model.Pagination;
import io.github.ibuildthecloud.url.UrlBuilder;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

import javax.inject.Inject;

import org.apache.commons.beanutils.PropertyUtils;

public abstract class AbstractBaseResourceManager implements ResourceManager {

    Map<String,Map<String,String>> linksCache = Collections.synchronizedMap(new WeakHashMap<String,Map<String,String>>());
    Set<Class<?>> resourcesToCreate = new HashSet<Class<?>>();
    protected SchemaFactory schemaFactory;
    protected ResourceManagerLocator locator;

    protected Object authorize(Object object) {
        return object;
    }

    protected void addResourceToCreateResponse(Class<?> clz) {
        this.resourcesToCreate.add(clz);
    }

    @Override
    public final Object getById(String type, String id, ListOptions options) {
        return authorize(getByIdInternal(type, id, options));
    }

    protected Object getByIdInternal(String type, String id, ListOptions options) {
        Map<Object,Object> criteria = getDefaultCriteria(true);
        criteria.put(TypeUtils.ID_FIELD, id);

        return getFirstFromList(listInternal(type, criteria, options));
    }

    @Override
    public final Object list(String type, ApiRequest request) {
        return authorize(listInternal(type, request));
    }

    protected Object listInternal(String type, ApiRequest request) {
        Map<Object,Object> criteria = new LinkedHashMap<Object, Object>(request.getConditions());
        criteria.putAll(getDefaultCriteria(false));
        return listInternal(type, criteria, new ListOptions(request));
    }

    @Override
    public final List<?> list(String type, Map<Object, Object> criteria, ListOptions options) {
        criteria.putAll(getDefaultCriteria(false));
        Object result = authorize(listInternal(type, criteria, options));
        return RequestUtils.toList(result);
    }

    protected abstract Object listInternal(String type, Map<Object, Object> criteria, ListOptions options);

    @Override
    public final Object create(String type, ApiRequest request) {
        return authorize(createInternal(type, request));
    }

    protected abstract Object createInternal(String type, ApiRequest request);


    @Override
    public final Object update(String type, String id, ApiRequest request) {
        Object object = getById(type, id, new ListOptions(request));
        if ( object == null ) {
            return null;
        }

        return updateInternal(type, id, object, request);
    }

    protected abstract Object updateInternal(String type, String id, Object obj, ApiRequest request);

    @Override
    public final Object delete(String type, String id, ApiRequest request) {
        Object object = getById(type, id, new ListOptions(request));
        if ( object == null ) {
            return null;
        }

        return deleteInternal(type, id, object, request);
    }

    protected abstract Object deleteInternal(String type, String id, Object obj, ApiRequest request);

    @Override
    public final Object getLink(String type, String id, String link, ApiRequest request) {
        return authorize(getLinkInternal(type, id, link, request));
    }

    protected abstract Object getLinkInternal(String type, String id, String link, ApiRequest request);

    protected Map<Object,Object> getDefaultCriteria(boolean byId) {
        return new HashMap<Object, Object>();
    }

    protected Long getMarker(Pagination pagination) {
        if ( pagination == null ) {
            return null;
        }

        String marker = pagination.getMarker();
        if ( marker == null ) {
            return null;
        }

        Object obj = ApiContext.getContext().getIdFormatter().parseId(marker);
        if ( obj instanceof Long ) {
            return (Long)obj;
        } else if ( obj != null ) {
            try {
                return new Long(obj.toString());
            } catch ( NumberFormatException e ) {
                // ignore
            }
        }

        return null;
    }


    @Override
    public Collection convertResponse(List<?> list, ApiRequest request) {
        return createCollection(list, request);
    }

    @Override
    public Resource convertResponse(Object obj, ApiRequest request) {
        return createResource(obj, ApiContext.getContext().getIdFormatter(), request);
    }

    protected Collection createCollection(List<?> list, ApiRequest request) {
        CollectionImpl collection = new CollectionImpl();
        if ( request != null ) {
            collection.setResourceType(getCollectionType(list, request));
        }

        IdFormatter formatter = ApiContext.getContext().getIdFormatter();

        addSort(collection, request);
        addPagination(list, collection, request);
        addFilters(collection, request);

        for ( Object obj : list ) {
            Resource resource = createResource(obj, formatter, request);
            if ( resource != null ) {
                collection.getData().add(resource);
                if ( collection.getResourceType() == null ) {
                    collection.setResourceType(resource.getType());
                }
            }
        }

        return collection;
    }

    protected String getCollectionType(List<?> list, ApiRequest request) {
        return request.getType();
    }

    protected void addFilters(CollectionImpl collection, ApiRequest request) {
        Schema schema = schemaFactory.getSchema(collection.getResourceType());
        Map<String,List<Condition>> conditions = new TreeMap<String, List<Condition>>(request.getConditions());
        for ( String key : schema.getCollectionFilters().keySet() ) {
            if ( ! conditions.containsKey(key) ) {
                conditions.put(key, null);
            }
        }
        collection.setFilters(conditions);
    }

    protected void addPagination(List<?> list, CollectionImpl collection, ApiRequest request) {
        Pagination pagination = request.getPagination();
        if ( pagination == null ) {
            return;
        }

        Integer limit = pagination.getLimit();
        if ( limit != null && list.size() > limit ) {
            Long lastId = getLastId(list);
            URL nextUrl = lastId == null ? null : ApiContext.getUrlBuilder().next(lastId);
            if ( nextUrl != null ) {
                list.remove(list.size()-1);
                pagination.setPartial(true);
                pagination.setNext(nextUrl);
            }
        }

        collection.setPagination(pagination);
    }

    protected Long getLastId(List<?> list) {
        Object last = list.get(list.size()-1);
        try {
            Object id = PropertyUtils.getProperty(last, TypeUtils.ID_FIELD);
            return id == null ? null : new Long(id.toString());
        } catch (IllegalAccessException e) {
        } catch (InvocationTargetException e) {
        } catch (NoSuchMethodException e) {
        } catch (NumberFormatException nfe) {
        }

        return null;
    }

    protected void addSort(CollectionImpl collection, ApiRequest request) {
        UrlBuilder urlBuilder = ApiContext.getUrlBuilder();
        Set<String> sortLinks = getSortLinks(schemaFactory, collection.getResourceType());
        Map<String,URL> sortLinkMap = new TreeMap<String,URL>();
        for ( String sortLink : sortLinks ) {
            URL sortUrl = urlBuilder.sort(sortLink);
            if ( sortUrl != null ) {
                sortLinkMap.put(sortLink, sortUrl);
            }
        }

        collection.setSortLinks(sortLinkMap);
        collection.setSort(request.getSort());
    }

    protected Set<String> getSortLinks(SchemaFactory schemaFactory, String type) {
        String key = schemaFactory.getId() + ":sortlinks:" + type;
        Map<String,String> links = linksCache.get(key);
        if ( links != null )
            return links.keySet();

        links = new HashMap<String,String>();
        Schema schema = schemaFactory.getSchema(type);
        if ( schema == null ) {
            return Collections.emptySet();
        }

        for ( String name : schema.getCollectionFilters().keySet() ) {
            links.put(name, name);
        }

        linksCache.put(key, links);
        return links.keySet();
    }

    protected Resource createResource(Object obj, IdFormatter idFormatter, ApiRequest apiRequest) {
        if ( obj == null )
            return null;

        if ( obj instanceof Resource )
            return (Resource)obj;

        if ( resourcesToCreate.size() > 0 && ! resourcesToCreate.contains(obj.getClass()) ) {
            String type = locator.getType(obj.getClass());
            ResourceManager rm = locator.getResourceManagerByType(type);
            if ( rm != null ) {
                return rm.convertResponse(obj, apiRequest);
            }
        }

        Schema schema = getSchemaForDisplay(obj);
        if ( schema == null ) {
            return null;
        }

        Resource resource = constructResource(idFormatter, schema, obj);
        addLinks(obj, schema, resource);
        addActions(obj, schema, resource);

        return resource;
    }

    protected Schema getSchemaForDisplay(Object obj) {
        return schemaFactory.getSchema(obj.getClass());
    }

    protected void addActions(Object obj, Schema schema, Resource resource) {
        Map<String,Action> actions = schema.getResourceActions();

        if ( actions == null || actions.size() == 0 ) {
            return;
        }

        UrlBuilder urlBuilder = ApiContext.getUrlBuilder();

        for ( String name : actions.keySet() ) {
            resource.getActions().put(name, urlBuilder.actionLink(resource, name));
        }
    }

    protected Resource constructResource(IdFormatter idFormatter, Schema schema, Object obj) {
        return new WrappedResource(idFormatter, schema, obj);
    }

    protected void addLinks(Object obj, Schema schema, Resource resource) {
        Map<String,URL> links = resource.getLinks();

        for ( Map.Entry<String,String> entry : getLinks(resource).entrySet() ) {
            String linkName = entry.getKey();
            String propName = entry.getValue();

            URL link = ApiContext.getUrlBuilder().resourceLink(resource, linkName);
            if ( link == null ) {
                continue;
            }

            if ( propName != null && resource.getFields().get(propName) == null ) {
                continue;
            }

            links.put(linkName, link);
        }
    }

    protected Map<String, String> getLinks(Resource resource) {
        return new HashMap<String, String>();
    }

    public static Object getFirstFromList(Object obj) {
        if ( obj instanceof Collection ) {
            return getFirstFromList(((Collection)obj).getData());
        }

        if ( obj instanceof List ) {
            List<?> list = (List<?>)obj;
            return list.size() > 0 ? list.get(0) : null;
        }

        return null;
    }

    @Override
    public boolean handleException(Throwable t, ApiRequest request) {
        return false;
    }

    @Override
    public final Object resourceAction(String type, ApiRequest request) {
        Object resource = getById(type, request.getId(), new ListOptions());

        if ( resource == null ) {
            return null;
        }

        return resourceActionInternal(resource, request);
    }

    protected abstract Object resourceActionInternal(Object obj, ApiRequest request);

    @Override
    public final Object collectionAction(String type, ApiRequest request) {
        Object resources = list(type, request);
        if ( resources == null ) {
            return null;
        }

        return collectionActionInternal(resources, request);
    }

    protected abstract Object collectionActionInternal(Object resources, ApiRequest request);

    public SchemaFactory getSchemaFactory() {
        return schemaFactory;
    }

    @Inject
    public void setSchemaFactory(SchemaFactory schemaFactory) {
        this.schemaFactory = schemaFactory;
    }

    public ResourceManagerLocator getLocator() {
        return locator;
    }

    @Inject
    public void setLocator(ResourceManagerLocator locator) {
        this.locator = locator;
    }

}