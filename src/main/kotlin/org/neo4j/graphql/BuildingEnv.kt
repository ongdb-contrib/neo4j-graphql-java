package org.neo4j.graphql

import graphql.schema.*

class BuildingEnv(val types: MutableMap<String, GraphQLType>) {

    private val typesForRelation = types.values
        .filterIsInstance<GraphQLObjectType>()
        .filter { it.getDirective(DirectiveConstants.RELATION) != null }
        .map { it.getDirectiveArgument<String>(DirectiveConstants.RELATION, DirectiveConstants.RELATION_NAME, null)!! to it.name }
        .toMap()

    fun buildFieldDefinition(
            prefix: String,
            resultType: GraphQLOutputType,
            scalarFields: List<GraphQLFieldDefinition>,
            nullableResult: Boolean,
            forceOptionalProvider: (field: GraphQLFieldDefinition) -> Boolean = { false }
    ): GraphQLFieldDefinition.Builder {
        var type: GraphQLOutputType = resultType
        if (!nullableResult) {
            type = GraphQLNonNull(type)
        }
        return GraphQLFieldDefinition.newFieldDefinition()
            .name("$prefix${resultType.name}")
            .arguments(getInputValueDefinitions(scalarFields, forceOptionalProvider))
            .type(type.ref() as GraphQLOutputType)
    }

    fun getInputValueDefinitions(
            relevantFields: List<GraphQLFieldDefinition>,
            forceOptionalProvider: (field: GraphQLFieldDefinition) -> Boolean): List<GraphQLArgument> {
        return relevantFields.map { field ->
            var type = field.type as GraphQLType
            type = getInputType(type)
            type = if (forceOptionalProvider(field)) {
                (type as? GraphQLNonNull)?.wrappedType ?: type
            } else {
                type
            }
            input(field.name, type)
        }
    }

    /**
     * add the given operation to the corresponding rootType
     */
    fun addOperation(rootTypeName: String, fieldDefinition: GraphQLFieldDefinition) {
        val rootType = types[rootTypeName]
        types[rootTypeName] = if (rootType == null) {
            val builder = GraphQLObjectType.newObject()
            builder.name(rootTypeName)
                .field(fieldDefinition)
                .build()
        } else {
            val existingRootType = (rootType as? GraphQLObjectType
                    ?: throw IllegalStateException("root type $rootTypeName is not an object type but ${rootType.javaClass}"))
            if (existingRootType.getFieldDefinition(fieldDefinition.name) != null) {
                return // definition already exists, we don't override it
            }
            existingRootType
                .transform { builder -> builder.field(fieldDefinition) }
        }
    }

    fun addFilterType(type: GraphQLFieldsContainer, createdTypes: MutableSet<String> = mutableSetOf()): String {
        val filterName = "_${type.name}Filter"
        if (createdTypes.contains(filterName)) {
            return filterName
        }
        val existingFilterType = types[filterName]
        if (existingFilterType != null) {
            return (existingFilterType as? GraphQLInputType)?.name
                    ?: throw IllegalStateException("Filter type $filterName is already defined but not an input type")
        }
        createdTypes.add(filterName)
        val builder = GraphQLInputObjectType.newInputObject()
            .name(filterName)
        listOf("AND", "OR", "NOT").forEach {
            builder.field(GraphQLInputObjectField.newInputObjectField()
                .name(it)
                .type(GraphQLList(GraphQLNonNull(GraphQLTypeReference(filterName)))))
        }
        type.fieldDefinitions
            .filter { it.dynamicPrefix() == null } // TODO currently we do not support filtering on dynamic properties
            .forEach { field ->
                val typeDefinition = field.type.inner()
                val filterType = when {
                    typeDefinition.isNeo4jType() -> getInputType(typeDefinition).name
                    typeDefinition.isScalar() -> typeDefinition.innerName()
                    typeDefinition is GraphQLEnumType -> typeDefinition.innerName()
                    else -> addFilterType(getInnerFieldsContainer(typeDefinition), createdTypes)
                }

                if (field.isRelationship()) {
                    RelationOperator.createRelationFilterFields(type, field, filterType, builder)
                } else {
                    FieldOperator.forType(types[filterType] ?: typeDefinition)
                        .forEach { op -> builder.addFilterField(op.fieldName(field.name), op.list, filterType) }
                }

            }
        types[filterName] = builder.build()
        return filterName
    }

    fun addOrdering(type: GraphQLFieldsContainer): String? {
        val orderingName = "_${type.name}Ordering"
        var existingOrderingType = types[orderingName]
        if (existingOrderingType != null) {
            return (existingOrderingType as? GraphQLInputType)?.name
                    ?: throw IllegalStateException("Ordering type $type.name is already defined but not an input type")
        }
        val sortingFields = type.fieldDefinitions
            .filter { it.type.isScalar() || it.isNeo4jType() }
            .sortedByDescending { it.isID() }
        if (sortingFields.isEmpty()) {
            return null
        }
        existingOrderingType = GraphQLEnumType.newEnum()
            .name(orderingName)
            .values(sortingFields.flatMap { fd ->
                listOf("_asc", "_desc")
                    .map {
                        GraphQLEnumValueDefinition
                            .newEnumValueDefinition()
                            .name(fd.name + it)
                            .value(fd.name + it)
                            .build()
                    }
            })
            .build()
        types[orderingName] = existingOrderingType
        return orderingName
    }

    fun addInputType(inputName: String, relevantFields: List<GraphQLFieldDefinition>): GraphQLInputType {
        var inputType = types[inputName]
        if (inputType != null) {
            return inputType as? GraphQLInputType
                    ?: throw IllegalStateException("Filter type $inputName is already defined but not an input type")
        }
        inputType = getInputType(inputName, relevantFields)
        types[inputName] = inputType
        return inputType
    }

    fun getTypeForRelation(nameOfRelation: String): GraphQLObjectType? {
        return typesForRelation[nameOfRelation]?.let { types[it] } as? GraphQLObjectType
    }

    private fun getInputType(inputName: String, relevantFields: List<GraphQLFieldDefinition>): GraphQLInputObjectType {
        return GraphQLInputObjectType.newInputObject()
            .name(inputName)
            .fields(getInputValueDefinitions(relevantFields))
            .build()
    }

    private fun getInputValueDefinitions(relevantFields: List<GraphQLFieldDefinition>): List<GraphQLInputObjectField> {
        return relevantFields.map {
            // just make evrything optional
            val type = (it.type as? GraphQLNonNull)?.wrappedType ?: it.type
            GraphQLInputObjectField
                .newInputObjectField()
                .name(it.name)
                .type(getInputType(type).ref() as GraphQLInputType)
                .build()
        }
    }

    private fun getInnerFieldsContainer(type: GraphQLType): GraphQLFieldsContainer {
        var innerType = type.inner()
        if (innerType is GraphQLTypeReference) {
            innerType = types[innerType.name]
                    ?: throw IllegalArgumentException("${innerType.name} is unknown")
        }
        return innerType as? GraphQLFieldsContainer
                ?: throw IllegalArgumentException("${innerType.name} is neither an object nor an interface")
    }

    private fun getInputType(type: GraphQLType): GraphQLInputType {
        val inner = type.inner()
        if (inner is GraphQLInputType) {
            return type as GraphQLInputType
        }
        if (inner.isNeo4jType()) {
            return neo4jTypeDefinitions
                .find { it.typeDefinition == inner.name }
                ?.let { types[it.inputDefinition] } as? GraphQLInputType
                    ?: throw IllegalArgumentException("Cannot find input type for ${inner.name}")
        }
        return type as? GraphQLInputType
                ?: throw IllegalArgumentException("${type.name} is not allowed for input")
    }

}