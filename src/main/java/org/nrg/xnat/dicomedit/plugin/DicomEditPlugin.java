package org.nrg.xnat.dicomedit.plugin;

import java.util.HashSet;
import java.util.Set;

import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.xnat.restlet.XnatRestletExtensions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@XnatPlugin(value = "dicomEditPlugin", name = "Dicom Edit Plugin", description = "Dicom Edit Plugin")
@ComponentScan({"com.radiologics.dicomedit"})
@Configuration
public class DicomEditPlugin {
	
	@Bean(name = "dicomEditRestletLookup")
	XnatRestletExtensions lookupRestlets() {
		Set<String> packages = new HashSet<String>();
		packages.add("org.nrg.xnat.helpers.dicom");
		return new XnatRestletExtensions(packages);
	}
}