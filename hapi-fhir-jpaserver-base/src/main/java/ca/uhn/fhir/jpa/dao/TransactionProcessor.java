package ca.uhn.fhir.jpa.dao;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2021 Smile CDR, Inc.
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
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.config.HapiFhirHibernateJpaDialect;
import ca.uhn.fhir.jpa.dao.index.IdHelperService;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamToken;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.util.StopWatch;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.Validate;
import org.hibernate.internal.SessionImpl;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import javax.persistence.PersistenceException;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ca.uhn.fhir.jpa.dao.index.IdHelperService.EMPTY_PREDICATE_ARRAY;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class TransactionProcessor extends BaseTransactionProcessor {

	public static final Pattern SINGLE_PARAMETER_MATCH_URL_PATTERN = Pattern.compile("^[^?]+[?][a-z0-9-]+=[^&,]+$");
	private static final Logger ourLog = LoggerFactory.getLogger(TransactionProcessor.class);
	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;
	@Autowired(required = false)
	private HapiFhirHibernateJpaDialect myHapiFhirHibernateJpaDialect;
	@Autowired
	private IdHelperService myIdHelperService;
	@Autowired
	private PartitionSettings myPartitionSettings;
	@Autowired
	private DaoConfig myDaoConfig;
	@Autowired
	private FhirContext myFhirContext;
	@Autowired
	private MatchResourceUrlService myMatchResourceUrlService;
	@Autowired
	private MatchUrlService myMatchUrlService;
	@Autowired
	private IRequestPartitionHelperSvc myRequestPartitionSvc;


	public void setEntityManagerForUnitTest(EntityManager theEntityManager) {
		myEntityManager = theEntityManager;
	}

	@Override
	protected void validateDependencies() {
		super.validateDependencies();

		Validate.notNull(myEntityManager);
	}

	@VisibleForTesting
	public void setFhirContextForUnitTest(FhirContext theFhirContext) {
		myFhirContext = theFhirContext;
	}

	@Override
	protected Map<IBase, IIdType> doTransactionWriteOperations(final RequestDetails theRequest, String theActionName, TransactionDetails theTransactionDetails, Set<IIdType> theAllIds,
																				  Map<IIdType, IIdType> theIdSubstitutions, Map<IIdType, DaoMethodOutcome> theIdToPersistedOutcome, IBaseBundle theResponse, IdentityHashMap<IBase, Integer> theOriginalRequestOrder, List<IBase> theEntries, StopWatch theTransactionStopWatch) {

		ITransactionProcessorVersionAdapter versionAdapter = getVersionAdapter();
		RequestPartitionId requestPartitionId = null;
		if (!myPartitionSettings.isPartitioningEnabled()) {
			requestPartitionId = RequestPartitionId.allPartitions();
		} else {
			// If all entries in the transaction point to the exact same partition, we'll try and do a pre-fetch
			Set<RequestPartitionId> requestPartitionIdsForAllEntries = new HashSet<>();
			for (IBase nextEntry : theEntries) {
				IBaseResource resource = versionAdapter.getResource(nextEntry);
				if (resource != null) {
					RequestPartitionId requestPartition = myRequestPartitionSvc.determineCreatePartitionForRequest(theRequest, resource, myFhirContext.getResourceType(resource));
					requestPartitionIdsForAllEntries.add(requestPartition);
				}
			}
			if (requestPartitionIdsForAllEntries.size() == 1) {
				requestPartitionId = requestPartitionIdsForAllEntries.iterator().next();
			}
		}

		if (requestPartitionId != null) {

			Set<String> foundIds = new HashSet<>();
			List<Long> idsToPreFetch = new ArrayList<>();

			/*
			 * Pre-Fetch any resources that are referred to normally by ID, e.g.
			 * regular FHIR updates within the transaction.
			 */
			List<IIdType> idsToPreResolve = new ArrayList<>();
			for (IBase nextEntry : theEntries) {
				IBaseResource resource = versionAdapter.getResource(nextEntry);
				if (resource != null) {
					String fullUrl = versionAdapter.getFullUrl(nextEntry);
					boolean isPlaceholder = defaultString(fullUrl).startsWith("urn:");
					if (!isPlaceholder) {
						if (resource.getIdElement().hasIdPart() && resource.getIdElement().hasResourceType()) {
							idsToPreResolve.add(resource.getIdElement());
						}
					}
				}
			}
			List<ResourcePersistentId> outcome = myIdHelperService.resolveResourcePersistentIdsWithCache(requestPartitionId, idsToPreResolve);
			for (ResourcePersistentId next : outcome) {
				foundIds.add(next.getAssociatedResourceId().toUnqualifiedVersionless().getValue());
				theTransactionDetails.addResolvedResourceId(next.getAssociatedResourceId(), next);
				if (myDaoConfig.getResourceClientIdStrategy() != DaoConfig.ClientIdStrategyEnum.ANY || !next.getAssociatedResourceId().isIdPartValidLong()) {
					idsToPreFetch.add(next.getIdAsLong());
				}
			}
			for (IIdType next : idsToPreResolve) {
				if (!foundIds.contains(next.toUnqualifiedVersionless().getValue())) {
					theTransactionDetails.addResolvedResourceId(next.toUnqualifiedVersionless(), null);
				}
			}

			/*
			 * Pre-resolve any conditional URLs we can
			 */
			List<MatchUrlToResolve> searchParameterMapsToResolve = new ArrayList<>();
			for (IBase nextEntry : theEntries) {
				IBaseResource resource = versionAdapter.getResource(nextEntry);
				if (resource != null) {
					String verb = versionAdapter.getEntryRequestVerb(myFhirContext, nextEntry);
					String requestUrl = versionAdapter.getEntryRequestUrl(nextEntry);
					String requestIfNoneExist = versionAdapter.getEntryIfNoneExist(nextEntry);
					String resourceType = myFhirContext.getResourceType(resource);
					if ("PUT".equals(verb) && requestUrl != null && requestUrl.contains("?")) {
						ResourcePersistentId cachedId = myMatchResourceUrlService.processMatchUrlUsingCacheOnly(resourceType, requestUrl);
						if (cachedId != null) {
							idsToPreFetch.add(cachedId.getIdAsLong());
						} else if (SINGLE_PARAMETER_MATCH_URL_PATTERN.matcher(requestUrl).matches()) {
							RuntimeResourceDefinition resourceDefinition = myFhirContext.getResourceDefinition(resource);
							SearchParameterMap matchUrlSearchMap = myMatchUrlService.translateMatchUrl(requestUrl, resourceDefinition);
							searchParameterMapsToResolve.add(new MatchUrlToResolve(requestUrl, matchUrlSearchMap, resourceDefinition));
						}
					} else if ("POST".equals(verb) && requestIfNoneExist != null && requestIfNoneExist.contains("?")) {
						ResourcePersistentId cachedId = myMatchResourceUrlService.processMatchUrlUsingCacheOnly(resourceType, requestIfNoneExist);
						if (cachedId != null) {
							idsToPreFetch.add(cachedId.getIdAsLong());
						} else if (SINGLE_PARAMETER_MATCH_URL_PATTERN.matcher(requestIfNoneExist).matches()) {
							RuntimeResourceDefinition resourceDefinition = myFhirContext.getResourceDefinition(resource);
							SearchParameterMap matchUrlSearchMap = myMatchUrlService.translateMatchUrl(requestIfNoneExist, resourceDefinition);
							searchParameterMapsToResolve.add(new MatchUrlToResolve(requestIfNoneExist, matchUrlSearchMap, resourceDefinition));
						}
					}

				}
			}
			if (searchParameterMapsToResolve.size() > 0) {
				CriteriaBuilder cb = myEntityManager.getCriteriaBuilder();
				CriteriaQuery<ResourceIndexedSearchParamToken> cq = cb.createQuery(ResourceIndexedSearchParamToken.class);
				Root<ResourceIndexedSearchParamToken> from = cq.from(ResourceIndexedSearchParamToken.class);
				List<Predicate> orPredicates = new ArrayList<>();

				for (MatchUrlToResolve next : searchParameterMapsToResolve) {
					Collection<List<List<IQueryParameterType>>> values = next.myMatchUrlSearchMap.values();
					if (values.size() == 1) {
						List<List<IQueryParameterType>> andList = values.iterator().next();
						IQueryParameterType param = andList.get(0).get(0);

						if (param instanceof TokenParam) {
							TokenParam tokenParam = (TokenParam) param;
							Predicate hashPredicate = null;
							if (isNotBlank(tokenParam.getValue()) && isNotBlank(tokenParam.getSystem())) {
								next.myHashSystemAndValue = ResourceIndexedSearchParamToken.calculateHashSystemAndValue(myPartitionSettings, requestPartitionId, next.myResourceDefinition.getName(), next.myMatchUrlSearchMap.keySet().iterator().next(), tokenParam.getSystem(), tokenParam.getValue());
								hashPredicate = cb.equal(from.get("myHashSystemAndValue").as(Long.class), next.myHashSystemAndValue);
							} else if (isNotBlank(tokenParam.getValue())) {
								next.myHashValue = ResourceIndexedSearchParamToken.calculateHashValue(myPartitionSettings, requestPartitionId, next.myResourceDefinition.getName(), next.myMatchUrlSearchMap.keySet().iterator().next(), tokenParam.getValue());
								hashPredicate = cb.equal(from.get("myHashValue").as(Long.class), next.myHashValue);
							}

							if (hashPredicate != null) {

								if (myPartitionSettings.isPartitioningEnabled() && !myPartitionSettings.isIncludePartitionInSearchHashes()) {
									if (requestPartitionId.isDefaultPartition()) {
										Predicate partitionIdCriteria = cb.isNull(from.get("myPartitionIdValue").as(Integer.class));
										hashPredicate = cb.and(hashPredicate, partitionIdCriteria);
									} else if (!requestPartitionId.isAllPartitions()) {
										Predicate partitionIdCriteria = from.get("myPartitionIdValue").as(Integer.class).in(requestPartitionId.getPartitionIds());
										hashPredicate = cb.and(hashPredicate, partitionIdCriteria);
									}
								}

								orPredicates.add(hashPredicate);
							}
						}
					}

				}

				if (orPredicates.size() > 1) {
					cq.where(cb.or(orPredicates.toArray(EMPTY_PREDICATE_ARRAY)));

					TypedQuery<ResourceIndexedSearchParamToken> query = myEntityManager.createQuery(cq);
					List<ResourceIndexedSearchParamToken> results = query.getResultList();
					for (ResourceIndexedSearchParamToken nextResult : results) {

						for (MatchUrlToResolve nextSearchParameterMap : searchParameterMapsToResolve) {
							if (nextSearchParameterMap.myHashSystemAndValue != null && nextSearchParameterMap.myHashSystemAndValue.equals(nextResult.getHashSystemAndValue())) {
								idsToPreFetch.add(nextResult.getResourcePid());
								myMatchResourceUrlService.matchUrlResolved(theTransactionDetails, nextSearchParameterMap.myResourceDefinition.getName(), nextSearchParameterMap.myRequestUrl, new ResourcePersistentId(nextResult.getResourcePid()));
								theTransactionDetails.addResolvedMatchUrl(nextSearchParameterMap.myRequestUrl, new ResourcePersistentId(nextResult.getResourcePid()));
								nextSearchParameterMap.myResolved = true;
							}
							if (nextSearchParameterMap.myHashValue != null && nextSearchParameterMap.myHashValue.equals(nextResult.getHashValue())) {
								idsToPreFetch.add(nextResult.getResourcePid());
								myMatchResourceUrlService.matchUrlResolved(theTransactionDetails, nextSearchParameterMap.myResourceDefinition.getName(), nextSearchParameterMap.myRequestUrl, new ResourcePersistentId(nextResult.getResourcePid()));
								theTransactionDetails.addResolvedMatchUrl(nextSearchParameterMap.myRequestUrl, new ResourcePersistentId(nextResult.getResourcePid()));
								nextSearchParameterMap.myResolved = true;
							}

						}

					}

					for (MatchUrlToResolve nextSearchParameterMap : searchParameterMapsToResolve) {
						// No matches
						if (!nextSearchParameterMap.myResolved) {
							theTransactionDetails.addResolvedMatchUrl(nextSearchParameterMap.myRequestUrl, TransactionDetails.NOT_FOUND);
						}
					}

				}
			}


			/*
			 * Pre-fetch the resources we're touching in this transaction in mass - this reduced the
			 * number of database round trips.
			 *
			 * The thresholds below are kind of arbitrary. It's not
			 * actually guaranteed that this pre-fetching will help (e.g. if a Bundle contains
			 * a bundle of NOP conditional creates for example, the pre-fetching is actually loading
			 * more data than would otherwise be loaded).
			 *
			 * However, for realistic average workloads, this should reduce the number of round trips.
			 */
			if (idsToPreFetch.size() > 2) {
				List<ResourceTable> loadedResourceTableEntries = preFetchIndexes(idsToPreFetch, "forcedId", "myForcedId");

				if (loadedResourceTableEntries.stream().filter(t -> t.isParamsStringPopulated()).count() > 1) {
					preFetchIndexes(idsToPreFetch, "string", "myParamsString");
				}
				if (loadedResourceTableEntries.stream().filter(t -> t.isParamsTokenPopulated()).count() > 1) {
					preFetchIndexes(idsToPreFetch, "token", "myParamsToken");
				}
				if (loadedResourceTableEntries.stream().filter(t -> t.isParamsDatePopulated()).count() > 1) {
					preFetchIndexes(idsToPreFetch, "date", "myParamsDate");
				}
				if (loadedResourceTableEntries.stream().filter(t -> t.isParamsDatePopulated()).count() > 1) {
					preFetchIndexes(idsToPreFetch, "quantity", "myParamsQuantity");
				}
				if (loadedResourceTableEntries.stream().filter(t -> t.isHasLinks()).count() > 1) {
					preFetchIndexes(idsToPreFetch, "resourceLinks", "myResourceLinks");
				}

			}

		}

		return super.doTransactionWriteOperations(theRequest, theActionName, theTransactionDetails, theAllIds, theIdSubstitutions, theIdToPersistedOutcome, theResponse, theOriginalRequestOrder, theEntries, theTransactionStopWatch);
	}

	private List<ResourceTable> preFetchIndexes(List<Long> ids, String typeDesc, String fieldName) {
		TypedQuery<ResourceTable> query = myEntityManager.createQuery("FROM ResourceTable r LEFT JOIN FETCH r." + fieldName + " WHERE r.myId IN ( :IDS )", ResourceTable.class);
		query.setParameter("IDS", ids);
		List<ResourceTable> indexFetchOutcome = query.getResultList();
		ourLog.debug("Pre-fetched {} {}} indexes", indexFetchOutcome.size(), typeDesc);
		return indexFetchOutcome;
	}

	@Override
	protected void flushSession(Map<IIdType, DaoMethodOutcome> theIdToPersistedOutcome) {
		try {
			int insertionCount;
			int updateCount;
			SessionImpl session = myEntityManager.unwrap(SessionImpl.class);
			if (session != null) {
				insertionCount = session.getActionQueue().numberOfInsertions();
				updateCount = session.getActionQueue().numberOfUpdates();
			} else {
				insertionCount = -1;
				updateCount = -1;
			}

			StopWatch sw = new StopWatch();
			myEntityManager.flush();
			ourLog.debug("Session flush took {}ms for {} inserts and {} updates", sw.getMillis(), insertionCount, updateCount);
		} catch (PersistenceException e) {
			if (myHapiFhirHibernateJpaDialect != null) {
				List<String> types = theIdToPersistedOutcome.keySet().stream().filter(t -> t != null).map(t -> t.getResourceType()).collect(Collectors.toList());
				String message = "Error flushing transaction with resource types: " + types;
				throw myHapiFhirHibernateJpaDialect.translate(e, message);
			}
			throw e;
		}
	}

	@VisibleForTesting
	public void setPartitionSettingsForUnitTest(PartitionSettings thePartitionSettings) {
		myPartitionSettings = thePartitionSettings;
	}

	@VisibleForTesting
	public void setIdHelperServiceForUnitTest(IdHelperService theIdHelperService) {
		myIdHelperService = theIdHelperService;
	}

	private static class MatchUrlToResolve {

		private final String myRequestUrl;
		private final SearchParameterMap myMatchUrlSearchMap;
		private final RuntimeResourceDefinition myResourceDefinition;
		public boolean myResolved;
		private Long myHashValue;
		private Long myHashSystemAndValue;

		public MatchUrlToResolve(String theRequestUrl, SearchParameterMap theMatchUrlSearchMap, RuntimeResourceDefinition theResourceDefinition) {
			myRequestUrl = theRequestUrl;
			myMatchUrlSearchMap = theMatchUrlSearchMap;
			myResourceDefinition = theResourceDefinition;
		}
	}
}
