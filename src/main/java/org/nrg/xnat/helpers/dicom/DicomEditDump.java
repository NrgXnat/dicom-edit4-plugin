//Copyright 2018 Radiologics, Inc
//Author: James Dickson <james@radiologics.com>
package org.nrg.xnat.helpers.dicom;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.radiologics.dicomedit.utils.DicomEditConfigMapper;

import org.dcm4che2.data.ElementDictionary;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.config.entities.Configuration;
import org.nrg.config.exceptions.ConfigServiceException;
import org.nrg.config.services.ConfigService;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.model.*;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.XFTTable;
import org.nrg.xft.exception.InvalidPermissionException;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.dicom.DicomHeaderDump;
import org.nrg.xnat.helpers.dicom.DicomSummaryHeaderDump;
import org.nrg.xnat.helpers.prearchive.PrearcTableBuilder;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.restlet.XnatRestlet;
import org.nrg.xnat.restlet.resources.SecureResource;
import org.nrg.xnat.utils.CatalogUtils;
import org.nrg.xnat.utils.CatalogUtils.CatEntryFilterI;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.resource.Representation;
import org.restlet.resource.ResourceException;
import org.restlet.resource.Variant;
import org.restlet.util.Template;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@XnatRestlet({ "/services/dicomeditdump"})
public final class DicomEditDump extends SecureResource {
	//dump all discovered fields from all dicom files in session,scan etc
	private static final String SUMMARY_VALUE = "true";
	private static final String SUMMARY_ATTR = "summary";
    // "src" attribute the contains the uri to the desired resources
    private static final String SRC_ATTR = "src";
    private static final String FIELD_PARAM = "field";
    // image type supported.
    private static final String imageType = "DICOM";
    private static final int MAXFILENUMBER=1;
    private static final ElementDictionary TAG_DICTIONARY = ElementDictionary.getDictionary();

    // The global environment
    private final Env env;

    /**
     * A global environment that contains the type of request has made and a
     * map of the parsed "src" uri.
     * @author aditya
     *
     */
    class Env {
        Map<String,Object> attrs = new HashMap<String, Object>();
        HeaderType h;
        ArchiveType a;
        ResourceType r;
        final String uri; 
        final Map<Integer,Set<String>> fields;

        Env(String uri, Map<Integer,Set<String>> fields){
            this.uri = uri;
            this.a = ArchiveType.UNKNOWN;
            this.h = HeaderType.UNKNOWN;
            this.r = ResourceType.UNKNOWN;
            this.fields = fields;
            this.determineArchiveType();
            this.determineHeaderType();
            this.determineResourceType();
        }

        ArchiveType getArchiveType() {
            return this.a;
        }
        HeaderType getHeaderType() {
            return this.h;
        }
        ResourceType getResourceType() {
            return this.r;
        }
        void determineArchiveType () {
            if (this.uri.startsWith("/prearchive/")){
                this.a = ArchiveType.PREARCHIVE;
            }
            else if (this.uri.startsWith("/archive/")) {
                this.a = ArchiveType.ARCHIVE;
            }
            else {
                this.a = ArchiveType.UNKNOWN;
            }
        }

        /**
         * If a summary is requested then the resource type defaults to SCAN.
         */
        void determineResourceType() {
            if (this.a != ArchiveType.UNKNOWN && this.h != HeaderType.UNKNOWN) {
                this.r = ResourceType.SCAN;
            }
        }

        /**
         * Find a matching template and update the global environment
         * @param _h
         */
        void visit (HeaderType _h) {
            List<Template> ts = _h.getTemplates();
            Iterator<Template> i = ts.iterator();
            while (i.hasNext()) {
                Template t = i.next();
                if (t.match(this.uri) != -1) {
                    t.parse(this.uri, this.attrs);
                    this.h = _h;
                    this.r = _h.getResourceType(t);
                    break;
                }
            }
        }

        void determineHeaderType() {
            for (HeaderType h : HeaderType.values()) {
                if (this.h == HeaderType.UNKNOWN) {
                    this.visit(h);
                }
                else {
                    // the uri has been parsed so quit looking
                }
            }
        }
    }


    /**
     * The dicom dump requested, either a specific file, or a general summary of the session
     * @author aditya
     *
     */
    private static enum HeaderType {
        FILE("/prearchive/projects/{PROJECT_ID}/{TIMESTAMP}/{EXPT_ID}/scans/{SCAN_ID}/resources/DICOM/files/{FILENAME}",
                "/archive/projects/{PROJECT_ID}/subjects/{SUBJECT_ID}/experiments/{EXPT_ID}/scans/{SCAN_ID}/resources/DICOM/files/{FILENAME}",
                "/archive/projects/{PROJECT_ID}/subjects/{SUBJECT_ID}/experiments/{EXPT_ID}/assessors/{SCAN_ID}/resources/DICOM/files/{FILENAME}",
                "/archive/projects/{PROJECT_ID}/subjects/{SUBJECT_ID}/experiments/{EXPT_ID}/recons/{SCAN_ID}/resources/DICOM/files/{FILENAME}",
                "/prearchive/projects/{PROJECT_ID}/{TIMESTAMP}/{EXPT_ID}/scans/{SCAN_ID}/resources/DICOM/files/{FILENAME}"
        ){
            private static final String FILENAME_PARAM = "FILENAME";
            private static final String PROJECT_PARAM = "PROJECT_ID";

            @Override
            CatFilterWithPath getFilter(final Env env, final UserI user) {
                final String project = (String) env.attrs.get(PROJECT_PARAM);
                final Object filename = env.attrs.get(FILENAME_PARAM);
                return new CatFilterWithPath() {
                    public boolean accept(CatEntryI entry) {
                        final File f = CatalogUtils.getFile(entry, path, project);
                        return f.getName().equals(filename);
                    }
                };
            }
        },
        
        SCAN("/archive/projects/{PROJECT_ID}/subjects/{SUBJECT_ID}/experiments/{EXPT_ID}/scans/{SCAN_ID}",
                "/archive/projects/{PROJECT_ID}/subjects/{SUBJECT_ID}/experiments/{EXPT_ID}/assessors/{SCAN_ID}",
                "/archive/projects/{PROJECT_ID}/subjects/{SUBJECT_ID}/experiments/{EXPT_ID}/recons/{SCAN_ID}",
                "/archive/projects/{PROJECT_ID}/experiments/{EXPT_ID}/scans/{SCAN_ID}",
                "/archive/projects/{PROJECT_ID}/experiments/{EXPT_ID}/assessors/{SCAN_ID}",
                "/archive/projects/{PROJECT_ID}/experiments/{EXPT_ID}/recons/{SCAN_ID}",
                "/prearchive/projects/{PROJECT_ID}/{TIMESTAMP}/{EXPT_ID}/scans/{SCAN_ID}"
        ),
        
        SESSION("/archive/projects/{PROJECT_ID}/subjects/{SUBJECT_ID}/experiments/{EXPT_ID}",
                "/archive/projects/{PROJECT_ID}/experiments/{EXPT_ID}",
                "/prearchive/projects/{PROJECT_ID}/{TIMESTAMP}/{EXPT_ID}"
        ),
        
        UNKNOWN(){
            @Override
            String retrieve(Env env, UserI user) { return null; }
        };

        private final ImmutableMap<Template,ResourceType> templates;

        private HeaderType(final String...templates) {
            // Convert the provided string templates to Template objects
            final ImmutableMap.Builder<Template,ResourceType> builder = ImmutableMap.builder();
            for (final String st : templates) {
                final Template t = new Template(st, Template.MODE_STARTS_WITH);
                final ResourceType r;
                if (st.contains("scans")) {
                    r = ResourceType.SCAN;
                } else if (st.contains("assessors")) {
                    r = ResourceType.ASSESSOR;
                } else if (st.contains("recons")) {
                    r = ResourceType.RECON;
                } else {
                    r = ResourceType.UNKNOWN;
                }
                builder.put(t, r);
            }
            this.templates = builder.build();
        }


        /**
         * The URI templates associated with type
         * @return
         */
        final List<Template> getTemplates() { return Lists.newArrayList(templates.keySet()); }

        /**
         * Based on the matching template output the correct resource type 
         * @param matchingTemplate
         * @return
         */
        final ResourceType getResourceType(final Template matchingTemplate) {
            final ResourceType r = templates.get(matchingTemplate);
            return null == r ? ResourceType.UNKNOWN : r;
        }

        /**
         * Returns the filter used to determine whether to use a provided catalog entry.
         * Default implementation always passes.
         * @param env
         * @param user
         * @return
         */
        CatFilterWithPath getFilter(Env env, UserI user) { return alwaysCatWithPath; }
        
        /**
         * Retrieve the file path to the first matching file.
         * 
         * Returns null if no DICOM file is found.
         * 
         * @param env
         * @param user
         * @return
         * @throws ClientException
         * @throws IOException
         * @throws InvalidPermissionException
         * @throws Exception
         */
        String retrieve(final Env env, final UserI user) throws Exception {
            final Iterable<File> matches = env.r.getFiles(env, user, getFilter(env, user), 1);
            for (final Iterator<File> fi = matches.iterator(); fi.hasNext(); ) {
                final File f = fi.next();
                if (null != f) {
                    return f.getAbsolutePath();
                }
            }
            return null;    
        }
        
        Iterable<File> retrieveAll(final Env env, final UserI user) throws Exception {
            final Iterable<File> matches = env.r.getFiles(env, user, getFilter(env, user), MAXFILENUMBER);
           
            return matches;    
        }
    };

    /**
     * The location of the requested session.
     *  
     * This class does some slightly unkosher things with global variables.
     * 
     * There are two global class variables, one holding the root path to a resource and the image session object associated
     * with requested session. These variables are updated *implicitly* by functions in the class, so the call order of the methods
     * in this class is important.
     *    
     * Please read the method comments for more details. 
     * @author aditya
     *
     */
    private static enum ArchiveType {
        PREARCHIVE(){
            @Override
            String getCatalogBasePath(XnatResourcecatalogI r) {
                return this.x.getPrearchivepath();
            }

            @Override
            XnatImagesessiondataI retrieve(Env env, UserI user) throws Exception, IOException, InvalidPermissionException{
                String project = (String) env.attrs.get("PROJECT_ID");
                String experiment = (String)env.attrs.get("EXPT_ID");
                String timestamp = (String) env.attrs.get("TIMESTAMP");
                File sessionDIR;
                File srcXML;
                sessionDIR = PrearcUtils.getPrearcSessionDir(user, project, timestamp, experiment,false);
                srcXML=new File(sessionDIR.getAbsolutePath()+".xml");
                XnatImagesessiondataI x = PrearcTableBuilder.parseSession(srcXML);
                this.x = x;
                return x;
            }
        },
        ARCHIVE(){
            @Override
            String getCatalogBasePath(XnatResourcecatalogI r) {
                return (new File(r.getUri())).getParent();
            }

            @Override
            XnatImagesessiondataI retrieve(Env env, UserI user) throws ClientException,IOException,InvalidPermissionException,Exception {
                String project = (String) env.attrs.get("PROJECT_ID");
                String experiment = (String)env.attrs.get("EXPT_ID");
                XnatImagesessiondata x = (XnatImagesessiondata) XnatExperimentdata.GetExptByProjectIdentifier(project, experiment,user, false);
                if (x == null || null == x.getId()) {
                    x = (XnatImagesessiondata) XnatExperimentdata.getXnatExperimentdatasById(experiment, user, false);
                    if (x != null && !x.hasProject(project)) {
                        x = null;
                    }
                }
                if (x == null) {
                    throw new ClientException(Status.CLIENT_ERROR_NOT_FOUND, 
                            "Experiment or project not found", 
                            new Exception ("Experiment or project not found"));
                }
                this.x = x;
                return x;
            }
        },

        UNKNOWN() {
            @Override
            String getCatalogBasePath(XnatResourcecatalogI r) {
                return null;
            }
            @Override
            XnatImagesessiondataI retrieve(Env env, UserI user) { 
                return null;
            }
        };

        XnatImagesessiondataI x = null;
        String rootPath = null;

        /**
         * Get base path for searching for catalog
         * @param r the resource
         * @return the base path
         */
        abstract String getCatalogBasePath(XnatResourcecatalogI r);

        /**
         * Retrieve the catalog for this resource. Additionally this also updates the
         * "rootPath" global class variable. This function is dependent on the
         * {@link XnatImageassessordataI} having been populated.
         * @param r the resource
         * @param project the project
         * @return the catalog bean
         */
        @Nullable
        private CatCatalogI getCatalog(XnatResourcecatalogI r, String project) {
            final String basePath = getCatalogBasePath(r);
            if (basePath == null) {
                return null;
            }
            try {
                CatalogUtils.CatalogData catalogData = CatalogUtils.CatalogData.getOrCreateAndClean(basePath, r,
                        false, project);
                this.rootPath = catalogData.catPath;
                return catalogData.catBean;
            } catch (ServerException e) {
                logger.error("Unable to retrieve catalog", e);
                return null;
            }
        }

        /**
         * Retrieve the image session object for this session. Additionally this also updates the
         * XnatImagesessiondataI global. 
         * @param env
         * @param user
         * @return
         * @throws ClientException
         * @throws IOException
         * @throws InvalidPermissionException
         * @throws Exception
         */
        abstract XnatImagesessiondataI retrieve(Env env, UserI user) throws ClientException, IOException, InvalidPermissionException, Exception;
    };

    /**
     * The type of resource requested.
     * @author aditya
     *
     */
    private static enum ResourceType {
        SCAN {
            Iterable<File> getFiles(Env env, UserI user, CatFilterWithPath filter, int enough) throws Exception {
                final XnatImagesessiondataI x = env.a.retrieve(env, user);
                final List<File> files = new ArrayList<File>();
                final Object scanID = env.attrs.get(URIManager.SCAN_ID);
                String []scanIds=StringUtils.split((String)scanID,',');
                for (final XnatImagescandataI scan : x.getScans_scan()){
                    if (null == scanID || scanID.equals(scan.getId()) || Arrays.asList(scanIds).contains(scan.getId())) {
                        final List<XnatResourcecatalogI> resources = scan.getFile();
                        files.addAll(this.findMatchingFile(env, resources, filter, enough));
                        /*if (files.size() >= enough) {
                            return files;
                        }*/
                    }
                }
                return files;
            }
        },

        ASSESSOR {
            Iterable<File> getFiles(Env env, UserI user, CatFilterWithPath filter, int enough) throws Exception {
                final XnatImagesessiondataI x = env.a.retrieve(env, user);
                final Object id = env.attrs.get(URIManager.SCAN_ID);
                final List<File> files = new ArrayList<File>();
                for (XnatImageassessordataI assessor : x.getAssessors_assessor()) {
                    if (null == id || id.equals(assessor.getId())) {
                        final List<XnatResourcecatalogI> resources = assessor.getResources_resource();
                        files.addAll(this.findMatchingFile(env, resources, filter, enough));
                        if (files.size() >= enough) {
                            return files;
                        }
                        final List<XnatResourcecatalogI> in_resources = assessor.getIn_file();
                        files.addAll(this.findMatchingFile(env, in_resources, filter, enough));
                        if (files.size() >= enough) {
                            return files;
                        }
                        final List<XnatResourcecatalogI> out_resources = assessor.getOut_file();
                        files.addAll(this.findMatchingFile(env, out_resources, filter, enough));
                        if (files.size() >= enough) {
                            return files;
                        }
                    }
                }
                return files;
            }
        },

        RECON {
            Iterable<File> getFiles(Env env, UserI user, CatFilterWithPath filter, int enough) throws Exception {
                final XnatImagesessiondataI x = env.a.retrieve(env,user);
                final Object id = env.attrs.get(URIManager.SCAN_ID);
                final Collection<File> files = new ArrayList<File>();
                for (XnatReconstructedimagedataI recon : x.getReconstructions_reconstructedimage()) {
                    if (null == id || id.equals(recon.getId())) {
                        List<XnatResourcecatalogI> in_resources = recon.getIn_file();
                        files.addAll(this.findMatchingFile(env, in_resources, filter, enough));
                        if (files.size() >= enough) {
                            return files;
                        }
                        List<XnatResourcecatalogI> out_resources = recon.getOut_file();
                        files.addAll(this.findMatchingFile(env, out_resources, filter, enough));
                        if (files.size() >= enough) {
                            return files;
                        }
                    }
                }
                return files;
            }
        },

        UNKNOWN {
            Iterable<File> getFiles(Env env, UserI user, CatFilterWithPath filter, int enough) {
                return Collections.emptyList();
            }
        };

        List<File> findMatchingFile(final Env env, final List<XnatResourcecatalogI> resources, final CatFilterWithPath filter, final int enough) {
            String project = (String) env.attrs.get("PROJECT_ID");
            final List<File> files = Lists.newArrayList();
            
            for (Object aresource : resources) {
            	//ignore other resource types (ecat ImageResource) for example
            	if (XnatResourcecatalogI.class.isAssignableFrom(aresource.getClass())){
            		
            		XnatResourcecatalogI resource=(XnatResourcecatalogI)aresource;
            		final String type = resource.getLabel();
                    if (type.equals(DicomEditDump.imageType)) {
                        final CatCatalogI catalog = env.a.getCatalog(resource, project);
                        if (catalog == null) {
                            continue;
                        }
                        filter.setPath(env.a.rootPath);
                        for (CatEntryI match : CatalogUtils.getEntriesByFilter(catalog, filter)) {
                            files.add(CatalogUtils.getFile(match, env.a.rootPath, project));
                            if (files.size() >= enough) {
                                return files;
                            }
                        }
                    }
            	}
            }
            return files;
        }

        /**
         * Retrieve the DICOM files at this resource level.
         * @param env
         * @param user
         * @param filter
         * @param enough
         * @return
         * @throws ClientException
         * @throws IOException
         * @throws InvalidPermissionException
         * @throws Exception
         */
        abstract Iterable<File> getFiles(Env env, 
                UserI user, 
                CatFilterWithPath filter,
                int enough) 
                throws ClientException, 
                IOException, 
                InvalidPermissionException, 
                Exception;
    }

    private static ImmutableMap<Integer,Set<String>> getFields(String[] fieldVals) {
        ImmutableMap.Builder<Integer,Set<String>> fieldsb = ImmutableMap.builder();
        for (final String field : fieldVals) {
            final String[] parts = field.split(":");
            final String tag_s = parts[0];
            final Set<String> subs = Sets.newHashSet();
            for (int i = 1; i < parts.length; i++) {
                subs.add(parts[i]);
            }

            int tag;
            try {
                tag = TAG_DICTIONARY.tagForName(tag_s);
            } catch (IllegalArgumentException e) {
                try {
                    tag = Integer.parseInt(tag_s, 16);
                } catch (NumberFormatException e1) {
                    throw new IllegalArgumentException("not a valid DICOM attribute tag: " + tag_s, e1);
                }
            }
            fieldsb.put(tag, subs);
        }
        return fieldsb.build();
    }

    public DicomEditDump(Context context, Request request, Response response) {
        super(context, request, response);

        if (!this.containsQueryVariable(DicomEditDump.SRC_ATTR)) {
            this.getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Please set the src parameter");
            env = null;
            return;
        }

        final Map<Integer,Set<String>> fields;
        try {
            fields = getFields(getQueryVariables(FIELD_PARAM));
        } catch (IllegalArgumentException e) {
            this.getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, e);
            env = null;
            return;
        }

        this.env = new Env(this.getQueryVariable(DicomEditDump.SRC_ATTR), fields);   

        getVariants().add(new Variant(MediaType.APPLICATION_JSON));
        getVariants().add(new Variant(MediaType.TEXT_HTML));
        getVariants().add(new Variant(MediaType.TEXT_XML));
         
    }

    
    
    
    
    
    
    public boolean allowPost() { return false; }
    public boolean allowPut() { return false; }

    /**
     * Enhance {@link CatEntryFilterI} to include a path
     * in its environment.
     * 
     * This is required because by the time we get down to 
     * iterating through the catalog entries we've lost
     * access to the absolute path to the resource. 
     * @author aditya
     *
     */
    static abstract class CatFilterWithPath implements CatEntryFilterI {
        String path;
        public void setPath (String path) {this.path = path;}
        public abstract boolean accept(CatEntryI entry);
    }

    final static CatFilterWithPath alwaysCatWithPath = new CatFilterWithPath() {
        { setPath(null); }
        public boolean accept(CatEntryI entry) { return true; }
    };

    public Representation represent(final Variant variant) throws ResourceException{
        final MediaType mt = overrideVariant(variant);
        XFTTable t = new XFTTable();
        try {
        	//need extra param
        	String summary=this.getQueryVariable(DicomEditDump.SUMMARY_ATTR);
            if (SUMMARY_VALUE.equals(summary)){
            	Iterable<File> files = this.env.h.retrieveAll(this.env, this.getUser());
            	DicomSummaryHeaderDump d = new DicomSummaryHeaderDump(files, env.fields);
                t = d.render();
            }else{//default..
            	String file = this.env.h.retrieve(this.env, this.getUser());
                DicomHeaderDump d = new DicomHeaderDump(file, env.fields);
                t = d.render();
            }
            t=whitelist(t);

        }
        catch (FileNotFoundException e){
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND, "There was an error rendering Dicom Header", e);
        }
        catch (IOException e){
            throw new ResourceException(Status.SERVER_ERROR_INTERNAL, "There was an error rendering Dicom Header", e);
        }
        catch (ClientException e) {
            throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST, "There was an error rendering Dicom Header", e);
        }
        catch (Throwable e) {
            throw new ResourceException(Status.SERVER_ERROR_INTERNAL, "There was an error rendering Dicom Header", e);
        }

        return this.representTable(t, mt, new Hashtable<String,Object>());
    }
    
    
    
    
    public String getWhiteList() throws ConfigServiceException {
        ConfigService configService = XDAT.getConfigService();

        // check project config
        String projectid = (String) env.attrs.get("PROJECT_ID");
        Configuration config =configService.getConfig("dicomedit", "script", XnatProjectdata.getProjectInfoIdFromStringId(projectid));
        if (config != null && config.getStatus().equals("enabled")) {
        	return (String)dicomEditConfigMapper.getDicomEditConfigAsMap(config).get("dicomedit_whitelist");
        }

        // if nothing there, check site config
        return XDAT.getSiteConfigurationProperty("dicomedit_whitelist", "");
    }
    public String getBlackList() throws ConfigServiceException {
        ConfigService configService = XDAT.getConfigService();

        // check project config
        String projectid = (String) env.attrs.get("PROJECT_ID");
        Configuration config =configService.getConfig("dicomedit", "script", XnatProjectdata.getProjectInfoIdFromStringId(projectid));
        if (config != null && config.getStatus().equals("enabled")) {
            return (String)dicomEditConfigMapper.getDicomEditConfigAsMap(config).get("dicomedit_blacklist");
        }

        // if nothing there, check site config
        return XDAT.getSiteConfigurationProperty("dicomedit_blacklist", "");
    }
    
    XFTTable whitelist(XFTTable table) throws ConfigServiceException{
    	XFTTable newtable=new XFTTable();
    	newtable.initTable(this.addString(table.getColumns(),"existing"));//adding existing field for edit comparison
    	
    	String dicomeditWhitelist = this.getWhiteList();
    	
    	String[] whitelistFields=StringUtils.split(dicomeditWhitelist,'\n');
    	List<String> whitelistFieldsArray=Arrays.asList(whitelistFields);
    	
    	String dicomeditBlacklist = this.getBlackList();
    	
    	String[] blackFields=StringUtils.split(dicomeditBlacklist,'\n');
    	List<String> blackFieldsArray=Arrays.asList(blackFields);
    	
    	
    	
    	ArrayList<Object[]> rows=table.rows();
    	for (int i = 0; i < rows.size(); i++) {
    		
    		String tag=(String)rows.get(i)[0];
    		String value=(String)rows.get(i)[3];
    		
    		String blackEntry=this.getFromList(tag,blackFieldsArray);
    		String whiteEntry=this.getFromList(tag,whitelistFieldsArray);
    		if ((whiteEntry!=null || whitelistFieldsArray.size()==0) && blackEntry==null){
    			Object[]row=table.rows().get(i);
    			//row[0]=this.getTag(whiteEntry);
    			//row=this.add((Object[]) row,(String)rows.get(i)[3]);
    			newtable.insertRow(new String[]{whiteEntry,"","",(String)rows.get(i)[3],this.getDescription(this.getTag(whiteEntry)),(String)rows.get(i)[3]});	
    		}
		}
    	//add tags when in whitelist but no existing value exists.
    	List<String> tags=new ArrayList<String>(table.rows().size());
    	for (int i = 0; i < rows.size(); i++) {
    		String tag=(String)rows.get(i)[0];
    		String value=(String)rows.get(i)[2];
    		tags.add(tag);
		}
    	
    	if(rows.size()>0){
	    	for (String whitelist : whitelistFieldsArray) {
	    		if (StringUtils.startsWith(whitelist, "(") && !tags.contains(this.getTag(whitelist))){
	    			newtable.insertRow(new String[]{whitelist,"","","",this.getDescription(this.getTag(whitelist)),""});	
	        	}
			} 
    	}
    
    	return newtable;
    	
    	
    }
    
    
    public String getFromList(String tag,List<String> blackorwhitelist) {
    		for (String value : blackorwhitelist) {
    	    	if(StringUtils.startsWith(value, "(")){
    	    		String listtag=this.getTag(value);
    	    		if (StringUtils.equals(listtag, tag)){
    	    			return value;
    	    		};
    	    	}
    		}
    	return null;
    }
    
    
    public String getDescription(String tag){
    	String is = tag;
    	is=StringUtils.remove(is, " ");
    	is=StringUtils.remove(is, "(");
    	is=StringUtils.remove(is, ")");
    	is=StringUtils.remove(is, ",");
    	is=StringUtils.remove(is, "\n");
		int hex=Integer.parseInt(is, 16);
		String description=org.dcm4che2.data.ElementDictionary.getDictionary().nameOf(hex);
		return description;
    }
    
    public String getTag(String whitelist) {
    	 Pattern p = Pattern.compile("(\\(\\d\\d\\d\\d,\\d\\d\\d[a-zA-Z0-9]\\))");
		 List <String> tags = new ArrayList<String>();
		 Matcher match=p.matcher(whitelist);
		 while(match.find()) {
				for (int i = 0; i < match.groupCount(); i++) 
				{
					 System.out.println(match.group(i));
					 tags.add(match.group(i));
			 	}
				System.out.println("match");
		 }
		 if (tags.size()>1) {
			return tags.get(1);
		 }
		 else {
			 if(tags.size()==0) {
				 return "";
			 }else {
				 return tags.get(0);
			 }
		 }
	}
   
    public  Object[] add(Object[] arr,  Object addition){
    	Object[] tempArr = new Object[arr.length+1];
        System.arraycopy(arr, 0, tempArr, 0, arr.length);
        
        tempArr[tempArr.length-1] =addition;
        return tempArr;
        
    }
    public  String[] addString(String[] arr,  String addition){
    	String[] tempArr = new String[arr.length+1];
        System.arraycopy(arr, 0, tempArr, 0, arr.length);
        
        tempArr[tempArr.length-1] =addition;
        return tempArr;
        
    }
     public static final DicomEditConfigMapper dicomEditConfigMapper = new DicomEditConfigMapper();

    
}
