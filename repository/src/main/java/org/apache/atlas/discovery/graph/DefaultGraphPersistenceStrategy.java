/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.discovery.graph;

import java.util.List;

import javax.inject.Inject;

import org.apache.atlas.AtlasException;
import org.apache.atlas.RequestContext;
import org.apache.atlas.groovy.GroovyExpression;
import org.apache.atlas.query.GraphPersistenceStrategies;
import org.apache.atlas.query.GraphPersistenceStrategies$class;
import org.apache.atlas.query.TypeUtils;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.repository.MetadataRepository;
import org.apache.atlas.repository.RepositoryException;
import org.apache.atlas.repository.graph.GraphBackedMetadataRepository;
import org.apache.atlas.repository.graph.GraphHelper;
import org.apache.atlas.repository.graphdb.AtlasEdgeDirection;
import org.apache.atlas.repository.graphdb.AtlasGraph;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.graphdb.GremlinVersion;
import org.apache.atlas.typesystem.ITypedReferenceableInstance;
import org.apache.atlas.typesystem.ITypedStruct;
import org.apache.atlas.typesystem.persistence.Id;
import org.apache.atlas.typesystem.types.AttributeInfo;
import org.apache.atlas.typesystem.types.ClassType;
import org.apache.atlas.typesystem.types.DataTypes;
import org.apache.atlas.typesystem.types.IDataType;
import org.apache.atlas.typesystem.types.Multiplicity;
import org.apache.atlas.typesystem.types.StructType;
import org.apache.atlas.typesystem.types.TraitType;
import org.apache.atlas.typesystem.types.TypeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

/**
 * Default implementation of GraphPersistenceStrategy.
 */
public class DefaultGraphPersistenceStrategy implements GraphPersistenceStrategies {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultGraphPersistenceStrategy.class);

    private final GraphBackedMetadataRepository metadataRepository;

    @Inject
    public DefaultGraphPersistenceStrategy(MetadataRepository metadataRepository) {
        this.metadataRepository = (GraphBackedMetadataRepository) metadataRepository;
    }

    @Override
    public String typeAttributeName() {
        return metadataRepository.getTypeAttributeName();
    }

    @Override
    public String superTypeAttributeName() {
        return metadataRepository.getSuperTypeAttributeName();
    }

    @Override
    public String edgeLabel(IDataType<?> dataType, AttributeInfo aInfo) {
        try {
            return metadataRepository.getEdgeLabel(dataType, aInfo);
        } catch (AtlasException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String traitLabel(IDataType<?> dataType, String traitName) {
        return metadataRepository.getTraitLabel(dataType, traitName);
    }

    @Override
    public String fieldNameInVertex(IDataType<?> dataType, AttributeInfo aInfo) {
        try {
            return metadataRepository.getFieldNameInVertex(dataType, aInfo);
        } catch (AtlasException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> traitNames(AtlasVertex AtlasVertex) {
        return GraphHelper.getTraitNames(AtlasVertex);
    }

    @Override
    public Id getIdFromVertex(String dataTypeName, AtlasVertex vertex) {
        return GraphHelper.getIdFromVertex(dataTypeName, vertex);
    }

    @Override
    public ITypedReferenceableInstance constructClassInstanceId(ClassType classType, Object value) {
        try {
            AtlasVertex classVertex = (AtlasVertex) value;
            ITypedReferenceableInstance classInstance = classType.createInstance(GraphHelper.getIdFromVertex(classVertex),
                    new String[0]);
            return classType.convert(classInstance, Multiplicity.OPTIONAL);
        } catch (AtlasException e) {
            LOG.error("error while constructing an instance", e);
        }
        return null;
    }

    @Override
    public <U> U constructInstance(IDataType<U> dataType, Object value) {
        try {
            switch (dataType.getTypeCategory()) {
            case PRIMITIVE:
            case ENUM:
                return dataType.convert(value, Multiplicity.OPTIONAL);
            case ARRAY:
                DataTypes.ArrayType arrType = (DataTypes.ArrayType) dataType;
                IDataType<?> elemType = arrType.getElemType();
                ImmutableCollection.Builder result = ImmutableList.builder();
                List list = (List) value;
                for(Object listElement : list) {
                    Object collectionEntry = constructCollectionEntry(elemType, listElement);
                    if(collectionEntry != null) {
                        result.add(collectionEntry);
                    }
                }
                return (U)result.build();
            case MAP:
                // todo
                break;

            case STRUCT:
                AtlasVertex structVertex = (AtlasVertex) value;
                StructType structType = (StructType) dataType;
                ITypedStruct structInstance = structType.createInstance();
                TypeSystem.IdType idType = TypeSystem.getInstance().getIdType();

                if (dataType.getName().equals(idType.getName())) {
                    structInstance.set(idType.typeNameAttrName(), GraphHelper.getSingleValuedProperty(structVertex, typeAttributeName(), String.class));
                    structInstance.set(idType.idAttrName(), GraphHelper.getSingleValuedProperty(structVertex, idAttributeName(), String.class));
                    String stateValue = GraphHelper.getSingleValuedProperty(structVertex, stateAttributeName(), String.class);
                    if (stateValue != null) {
                        structInstance.set(idType.stateAttrName(), stateValue);
                    }
                    structInstance.set(idType.versionAttrName(), structVertex.getProperty(versionAttributeName(), Integer.class));
                } else {
                    metadataRepository.getGraphToInstanceMapper()
                        .mapVertexToInstance(structVertex, structInstance, structType.fieldMapping().fields);
                }
                return dataType.convert(structInstance, Multiplicity.OPTIONAL);

            case TRAIT:
                AtlasVertex traitVertex = (AtlasVertex) value;
                TraitType traitType = (TraitType) dataType;
                ITypedStruct traitInstance = traitType.createInstance();
                // todo - this is not right, we should load the Instance associated with this
                // trait. for now just loading the trait struct.
                // metadataRepository.getGraphToInstanceMapper().mapVertexToTraitInstance(
                //        traitVertex, dataType.getName(), , traitType, traitInstance);
                metadataRepository.getGraphToInstanceMapper()
                    .mapVertexToInstance(traitVertex, traitInstance, traitType.fieldMapping().fields);
                break;

            case CLASS:
                AtlasVertex classVertex = (AtlasVertex) value;
                String guid = classVertex.getProperty(Constants.GUID_PROPERTY_KEY, String.class);
                // Check if the instance we need was previously loaded.
                ITypedReferenceableInstance classInstance = RequestContext.get().getInstance(guid);
                if (classInstance == null) {
                    classInstance = metadataRepository.getGraphToInstanceMapper().mapGraphToTypedInstance(guid, classVertex);
                }
                return dataType.convert(classInstance, Multiplicity.OPTIONAL);

            default:
                throw new UnsupportedOperationException("Load for type " + dataType + "is not supported");
            }
        } catch (AtlasException e) {
            LOG.error("error while constructing an instance", e);
        }

        return null;
    }

    public <U> U constructCollectionEntry(IDataType<U> elementType, Object value) throws AtlasException {
        switch (elementType.getTypeCategory()) {
        case PRIMITIVE:
        case ENUM:
            return constructInstance(elementType, value);
        //The array values in case of STRUCT, CLASS contain the edgeId if the outgoing edge which links to the STRUCT, CLASS vertex referenced
        case STRUCT:
        case CLASS:
            String edgeId = (String) value;
            return (U) metadataRepository.getGraphToInstanceMapper().getReferredEntity(edgeId, elementType);
        case ARRAY:
        case MAP:
        case TRAIT:
            return null;
        default:
            throw new UnsupportedOperationException("Load for type " + elementType + " in collections is not supported");
        }
    }

    @Override
    public String edgeLabel(TypeUtils.FieldInfo fInfo) {
        return fInfo.reverseDataType() == null ? edgeLabel(fInfo.dataType(), fInfo.attrInfo()) :
                edgeLabel(fInfo.reverseDataType(), fInfo.attrInfo());
    }

    @Override
    public AtlasEdgeDirection instanceToTraitEdgeDirection() {
        return AtlasEdgeDirection.OUT;
    }

    @Override
    public AtlasEdgeDirection traitToInstanceEdgeDirection() {
        return AtlasEdgeDirection.IN;
    }

    @Override
    public String idAttributeName() {
        return metadataRepository.getIdAttributeName();
    }

    @Override
    public String stateAttributeName() {
        return metadataRepository.getStateAttributeName();
    }

    @Override
    public String versionAttributeName() {
        return metadataRepository.getVersionAttributeName();
    }

    @Override
    public boolean collectTypeInstancesIntoVar() {
        return GraphPersistenceStrategies$class.collectTypeInstancesIntoVar(this);
    }

    @Override
    public boolean filterBySubTypes() {
        return GraphPersistenceStrategies$class.filterBySubTypes(this);
    }

    @Override
    public boolean addGraphVertexPrefix(scala.collection.Traversable<GroovyExpression> preStatements) {
        return GraphPersistenceStrategies$class.addGraphVertexPrefix(this, preStatements);
    }

    @Override
    public GremlinVersion getSupportedGremlinVersion() {
        return GraphPersistenceStrategies$class.getSupportedGremlinVersion(this);
    }

    @Override
    public GroovyExpression generatePersisentToLogicalConversionExpression(GroovyExpression expr, IDataType<?> t) {
        return GraphPersistenceStrategies$class.generatePersisentToLogicalConversionExpression(this,expr, t);
    }

    @Override
    public GroovyExpression addInitialQueryCondition(GroovyExpression expr) {
        return GraphPersistenceStrategies$class.addInitialQueryCondition(this, expr);
    }

    @Override
    public boolean isPropertyValueConversionNeeded(IDataType<?> t) {
        return GraphPersistenceStrategies$class.isPropertyValueConversionNeeded(this, t);
    }

    @Override
    public AtlasGraph getGraph() throws RepositoryException {
        return metadataRepository.getGraph();
    }

}
