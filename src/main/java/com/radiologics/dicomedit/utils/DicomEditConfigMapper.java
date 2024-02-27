//Copyright 2018 Radiologics, Inc
//Author: James Dickson <james@radiologics.com>
package com.radiologics.dicomedit.utils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.nrg.config.entities.Configuration;
import org.nrg.framework.exceptions.NrgServiceError;
import org.nrg.framework.exceptions.NrgServiceRuntimeException;

public class DicomEditConfigMapper {
	 private  final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory());
	 private  final TypeReference<HashMap<String, String>> MAP_TYPE_REFERENCE = new TypeReference<HashMap<String, String>>() {};
	    
	    
	    public Map<String, String> getDicomEditConfigAsMap(Configuration configuration) {
	        if (configuration == null) {
	            return getDicomEditConfigMap();
	        }
	        return getDicomEditConfigAsMap(configuration.getContents());
	    }

	    public  Map<String, String> getDicomEditConfigAsMap(String contents) {
	        if (StringUtils.isBlank(contents)) {
	            return getDicomEditConfigMap();
	        }
	        try {
	            return MAPPER.readValue(contents, MAP_TYPE_REFERENCE);
	        } catch (IOException exception) {
	            throw new NrgServiceRuntimeException(NrgServiceError.Unknown, "Something went wrong unmarshalling the configuration.", exception);
	        }
	    }
	    
	    private  Map<String, String> getDicomEditConfigMap() {
	        Map<String, String> map = new HashMap<String, String>();
	        map.put("enabled", "false");
	        map.put("dicomedit_whitelist", "");
	        map.put("dicomedit_blacklist", "");
	        return map;
	    }
}
