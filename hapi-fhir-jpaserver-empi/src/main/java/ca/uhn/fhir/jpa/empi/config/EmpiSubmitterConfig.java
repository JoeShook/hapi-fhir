package ca.uhn.fhir.jpa.empi.config;

/*-
 * #%L
 * HAPI FHIR JPA Server - Enterprise Master Patient Index
 * %%
 * Copyright (C) 2014 - 2020 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.empi.api.IEmpiSettings;
import ca.uhn.fhir.empi.util.EIDHelper;
import ca.uhn.fhir.jpa.empi.interceptor.EmpiStorageInterceptor;
import ca.uhn.fhir.jpa.empi.interceptor.EmpiSubmitterInterceptorLoader;
import ca.uhn.fhir.jpa.empi.svc.EmpiSearchParamSvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmpiSubmitterConfig {
	@Bean
    EmpiStorageInterceptor empiStorageInterceptor() {
		return new EmpiStorageInterceptor();
	}

	@Bean
	EIDHelper eidHelper(FhirContext theFhirContext, IEmpiSettings theEmpiConfig) {
		return new EIDHelper(theFhirContext, theEmpiConfig);
	}

	@Bean
	EmpiSubmitterInterceptorLoader empiSubmitterInterceptorLoader() {
		return new EmpiSubmitterInterceptorLoader();
	}

	@Bean
	EmpiSearchParamSvc empiSearchParamSvc() {
		return new EmpiSearchParamSvc();
	}
}