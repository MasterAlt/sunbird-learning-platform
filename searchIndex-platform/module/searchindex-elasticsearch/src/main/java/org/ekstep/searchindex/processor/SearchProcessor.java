package org.ekstep.searchindex.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ekstep.searchindex.dto.SearchDTO;
import org.ekstep.searchindex.elasticsearch.ElasticSearchUtil;
import org.ekstep.searchindex.transformer.AggregationsResultTransformer;
import org.ekstep.searchindex.util.CompositeSearchConstants;

import com.google.gson.internal.LinkedTreeMap;

import io.searchbox.core.CountResult;
import io.searchbox.core.SearchResult;
import net.sf.json.util.JSONBuilder;
import net.sf.json.util.JSONStringer;

public class SearchProcessor {

	private ElasticSearchUtil elasticSearchUtil = new ElasticSearchUtil();

	@SuppressWarnings({ "unchecked" })
	public Map<String, Object> processSearch(SearchDTO searchDTO) throws IOException {
		List<Map<String, Object>> groupByFinalList = new ArrayList<Map<String, Object>>();
		Map<String, Object> response = new HashMap<String, Object>();
		String query = processSearchQuery(searchDTO, groupByFinalList, true);
		SearchResult searchResult = elasticSearchUtil.search(CompositeSearchConstants.COMPOSITE_SEARCH_INDEX, query);
		List<Object> results = elasticSearchUtil.getDocumentsFromSearchResult(searchResult, Map.class);
		response.put("results", results);
		LinkedTreeMap<String, Object> aggregations = (LinkedTreeMap<String, Object>) searchResult
				.getValue("aggregations");
		if (aggregations != null && !aggregations.isEmpty()) {
			AggregationsResultTransformer transformer =  new AggregationsResultTransformer();
			response.put("facets", (List<Map<String, Object>>)elasticSearchUtil.getCountFromAggregation(aggregations, groupByFinalList, transformer));
		}
		return response;
	}
	
	public Map<String, Object> processCount(SearchDTO searchDTO) throws IOException {
		List<Map<String, Object>> groupByFinalList = new ArrayList<Map<String, Object>>();
		Map<String, Object> response = new HashMap<String, Object>();
		String query = processSearchQuery(searchDTO, null, false);
		
		CountResult countResult = elasticSearchUtil.count(CompositeSearchConstants.COMPOSITE_SEARCH_INDEX, query);
		response.put("count", countResult.getCount());
		
		return response;
	}
	
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private String processSearchQuery(SearchDTO searchDTO, List<Map<String, Object>> groupByFinalList, boolean sort){
		List<Map> conditionsSetOne = new ArrayList<Map>();
		List<Map> conditionsSetArithmetic = new ArrayList<Map>();
		List<Map> conditionsSetMustNot = new ArrayList<Map>();
		Map<String, List> conditionsMap = new HashMap<String, List>();
		conditionsMap.put("Text", conditionsSetOne);
		conditionsMap.put("Arithmetic", conditionsSetArithmetic);
		conditionsMap.put("Not", conditionsSetMustNot);

		List<Map> properties = searchDTO.getProperties();

		String totalOperation = searchDTO.getOperation();
		for (Map<String, Object> property : properties) {
			String propertyName = (String) property.get("propertyName");
			if (propertyName.equals("*")) {
				propertyName = "all_fields";
			}
			String operation = (String) property.get("operation");
			List<Object> values;
			try {
				values = (List<Object>) property.get("values");
			} catch (Exception e) {
				values = Arrays.asList(property.get("values"));
			}
			String queryOperation = null;
			String conditionSet = null;
			switch (operation) {
			case CompositeSearchConstants.SEARCH_OPERATION_EQUAL: {
				queryOperation = "equal";
				conditionSet = "Text";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_NOT_EQUAL: {
				queryOperation = "equal";
				conditionSet = "Not";
				break;
			}

			case CompositeSearchConstants.SEARCH_OPERATION_ENDS_WITH: {
				queryOperation = "endsWith";
				conditionSet = "Text";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_LIKE:
			case CompositeSearchConstants.SEARCH_OPERATION_CONTAINS: {
				queryOperation = "like";
				conditionSet = "Text";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_NOT_LIKE: {
				queryOperation = "like";
				conditionSet = "Not";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_STARTS_WITH: {
				queryOperation = "prefix";
				conditionSet = "Text";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_EXISTS: {
				queryOperation = "exists";
				conditionSet = "Text";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_NOT_EXISTS: {
				queryOperation = "exists";
				conditionSet = "Not";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_GREATER_THAN: {
				queryOperation = ">";
				conditionSet = "Arithmetic";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_GREATER_THAN_EQUALS: {
				queryOperation = ">=";
				conditionSet = "Arithmetic";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_LESS_THAN: {
				queryOperation = "<";
				conditionSet = "Arithmetic";
				break;
			}
			case CompositeSearchConstants.SEARCH_OPERATION_LESS_THAN_EQUALS: {
				queryOperation = "<=";
				conditionSet = "Arithmetic";
				break;
			}
			}

			Map<String, Object> condition = new HashMap<String, Object>();
			if (values.size() > 1) {
				condition.put("operation", "bool");
				condition.put("operand", "should");
				ArrayList<Map> subConditions = new ArrayList<Map>();
				for (Object value : values) {
					Map<String, Object> subCondition = new HashMap<String, Object>();
					subCondition.put("operation", queryOperation);
					subCondition.put("fieldName", propertyName);
					subCondition.put("value", value);
					subConditions.add(subCondition);
				}
				condition.put("subConditions", subConditions);
			} else if (propertyName.equalsIgnoreCase("all_fields")) {
				List<String> queryFields = elasticSearchUtil.getQuerySearchFields();
				condition.put("operation", "bool");
				condition.put("operand", "should");
				ArrayList<Map> subConditions = new ArrayList<Map>();
				for (String field : queryFields) {
					Map<String, Object> subCondition = new HashMap<String, Object>();
					subCondition.put("operation", queryOperation);
					subCondition.put("fieldName", field);
					subCondition.put("value", values.get(0));
					subConditions.add(subCondition);
				}
				condition.put("subConditions", subConditions);
			} else {
				condition.put("operation", queryOperation);
				condition.put("fieldName", propertyName);
				condition.put("value", values.get(0));
			}
			conditionsMap.get(conditionSet).add(condition);
		}

		if (searchDTO.getFacets() != null && groupByFinalList != null) {
			for (String facet : searchDTO.getFacets()) {
				Map<String, Object> groupByMap = new HashMap<String, Object>();
				groupByMap.put("groupByParent", facet);
				groupByFinalList.add(groupByMap);
			}
		}
		elasticSearchUtil.setResultLimit(searchDTO.getLimit());
		
		if(sort){
			Map<String, String> sortBy = searchDTO.getSortBy();
			if(sortBy == null || sortBy.isEmpty()){
				sortBy = new HashMap<String, String>();
				sortBy.put("name", "asc");
				searchDTO.setSortBy(sortBy);
			}
		}
		String query = makeElasticSearchQuery(conditionsMap, totalOperation, groupByFinalList, searchDTO.getSortBy());
		return query;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private String makeElasticSearchQuery(Map<String, List> conditionsMap, String totalOperation,
			List<Map<String, Object>> groupByList, Map<String, String> sortBy) {
		JSONBuilder builder = new JSONStringer();
		builder.object();
		List<Map> textConditions = conditionsMap.get("Text");
		List<Map> arithmeticConditions = conditionsMap.get("Arithmetic");
		List<Map> notConditions = conditionsMap.get("Not");

		if ((textConditions != null && !textConditions.isEmpty())
				|| (arithmeticConditions != null && !arithmeticConditions.isEmpty())
				|| (notConditions != null && !notConditions.isEmpty())) {
			builder.key("query").object().key("filtered").object().key("query").object().key("bool").object();
		}

		if (textConditions != null && !textConditions.isEmpty()) {
			String allOperation = "should";
			if (totalOperation == "AND") {
				allOperation = "must";
			}
			builder.key(allOperation).array();
			for (Map textCondition : textConditions) {
				String conditionOperation = (String) textCondition.get("operation");
				if (conditionOperation.equalsIgnoreCase("bool")) {
					String operand = (String) textCondition.get("operand");
					builder.object().key("bool").object();
					builder.key(operand).array();
					List<Map> subConditions = (List<Map>) textCondition.get("subConditions");
					for (Map subCondition : subConditions) {
						builder.object();
						String queryOperation = (String) subCondition.get("operation");
						String fieldName = (String) subCondition.get("fieldName");
						Object value = subCondition.get("value");
						getConditionsQuery(queryOperation, fieldName, value, builder);
						builder.endObject();
					}
					builder.endArray();
					builder.endObject().endObject();
				} else {
					builder.object();
					String queryOperation = (String) textCondition.get("operation");
					String fieldName = (String) textCondition.get("fieldName");
					Object value = (Object) textCondition.get("value");
					getConditionsQuery(queryOperation, fieldName, value, builder);
					builder.endObject();

				}
			}
			builder.endArray();
		}

		if (arithmeticConditions != null && !arithmeticConditions.isEmpty()) {
			String allOperation = "||";
			String scriptOperation = "should";
			if (totalOperation == "AND") {
				allOperation = "&&";
				scriptOperation = "must";
			}
			builder.key(scriptOperation).array();

			builder.object().key("script").object().key("script");
			String overallScript = "";
			for (Map arithmeticCondition : arithmeticConditions) {
				String conditionOperation = (String) arithmeticCondition.get("operation");
				String conditionScript = "";
				if (conditionOperation.equalsIgnoreCase("bool")) {
					String operand = "||";
					StringBuffer finalScript = new StringBuffer();
					finalScript.append("(");
					List<Map> subConditions = (List<Map>) arithmeticCondition.get("subConditions");
					List<String> scripts = new ArrayList<String>();
					for (Map subCondition : subConditions) {
						StringBuffer script = new StringBuffer();
						String queryOperation = (String) subCondition.get("operation");
						String fieldName = (String) subCondition.get("fieldName");
						Object value = (Object) subCondition.get("value");
						script.append("doc['").append(fieldName).append("']").append(".value ").append(queryOperation)
								.append(" ").append(value);
						scripts.add(script.toString());
					}
					String tempScript = "";
					for (String script : scripts) {
						tempScript = tempScript + operand + script;
					}
					tempScript = tempScript.substring(2);
					finalScript.append(tempScript).append(")");
					conditionScript = finalScript.toString();
				} else {
					StringBuffer script = new StringBuffer();
					String queryOperation = (String) arithmeticCondition.get("operation");
					String fieldName = (String) arithmeticCondition.get("fieldName");
					Object value = (Object) arithmeticCondition.get("value");
					script.append("doc['").append(fieldName).append("']").append(".value ").append(queryOperation)
							.append(" ").append(value);
					conditionScript = script.toString();
				}
				overallScript = overallScript + allOperation + conditionScript;
			}
			builder.value(overallScript.substring(2));
			builder.endObject().endObject().endArray();
		}

		if (notConditions != null && !notConditions.isEmpty()) {
			String allOperation = "must_not";
			builder.key(allOperation).array();
			for (Map notCondition : notConditions) {
				String conditionOperation = (String) notCondition.get("operation");
				if (conditionOperation.equalsIgnoreCase("bool")) {
					String operand = (String) notCondition.get("operand");
					builder.object().key("bool").object();
					builder.key(operand).array();
					List<Map> subConditions = (List<Map>) notCondition.get("subConditions");
					for (Map subCondition : subConditions) {
						builder.object();
						String queryOperation = (String) subCondition.get("operation");
						String fieldName = (String) subCondition.get("fieldName");
						Object value = subCondition.get("value");
						getConditionsQuery(queryOperation, fieldName, value, builder);
						builder.endObject();
					}
					builder.endArray();
					builder.endObject().endObject();
				} else {
					builder.object();
					String queryOperation = (String) notCondition.get("operation");
					String fieldName = (String) notCondition.get("fieldName");
					Object value = notCondition.get("value");
					getConditionsQuery(queryOperation, fieldName, value, builder);
					builder.endObject();
				}
			}
			builder.endArray();
		}

		if ((textConditions != null && !textConditions.isEmpty())
				|| (arithmeticConditions != null && !arithmeticConditions.isEmpty())
				|| (notConditions != null && !notConditions.isEmpty())) {
			builder.endObject().endObject().endObject().endObject();
		}

		if (groupByList != null && !groupByList.isEmpty()) {
			builder.key("aggs").object();
			for (Map<String, Object> groupByMap : groupByList) {
				String groupByParent = (String) groupByMap.get("groupByParent");
				builder.key(groupByParent).object().key("terms").object().key("field").value(groupByParent + CompositeSearchConstants.RAW_FIELD_EXTENSION).key("size")
						.value(elasticSearchUtil.defaultResultLimit).endObject().endObject();

				List<String> groupByChildList = (List<String>) groupByMap.get("groupByChildList");
				if (groupByChildList != null && !groupByChildList.isEmpty()) {
					builder.key("aggs").object();
					for (String childGroupBy : groupByChildList) {
						builder.key(childGroupBy).object().key("terms").object().key("field").value(childGroupBy + CompositeSearchConstants.RAW_FIELD_EXTENSION)
								.key("size").value(elasticSearchUtil.defaultResultLimit).endObject().endObject();
					}
					builder.endObject();
				}
			}
			builder.endObject();
		}
		
		if(sortBy != null && !sortBy.isEmpty()){
			builder.key("sort").array();
			List<String> dateFields = elasticSearchUtil.getDateFields();
			for(Map.Entry<String, String> entry: sortBy.entrySet()){
				String fieldName;
				if(dateFields.contains(entry.getKey())){
					fieldName = entry.getKey();
				}else{
					fieldName = entry.getKey() + CompositeSearchConstants.RAW_FIELD_EXTENSION;
				}
				builder.object().key(fieldName).value(entry.getValue()).endObject();
			}
			builder.endArray();
		}

		builder.endObject();
		return builder.toString();
	}

	private void getConditionsQuery(String queryOperation, String fieldName, Object value, JSONBuilder builder) {
		switch (queryOperation) {
		case "equal": {
			builder.key("match_phrase").object().key(fieldName + CompositeSearchConstants.RAW_FIELD_EXTENSION).value(value).endObject();
			break;
		}
		case "like": {
			builder.key("match_phrase").object().key(fieldName).value(value).endObject();
			break;
		}
		case "prefix": {
			String stringValue = (String) value;
			builder.key("query").object().key("prefix").object().key(fieldName + CompositeSearchConstants.RAW_FIELD_EXTENSION)
					.value(stringValue.toLowerCase()).endObject().endObject();
			break;
		}
		case "exists": {
			builder.key("exists").object().key("field").value(value).endObject();
			break;
		}
		case "endsWith": {
			String stringValue = (String) value;
			builder.key("query").object().key("wildcard").object().key(fieldName + CompositeSearchConstants.RAW_FIELD_EXTENSION)
					.value("*" + stringValue.toLowerCase()).endObject().endObject();
			break;
		}
		}
	}
}
