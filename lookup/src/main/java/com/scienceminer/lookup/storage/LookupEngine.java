package com.scienceminer.lookup.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rockymadden.stringmetric.similarity.RatcliffObershelpMetric;
import com.scienceminer.lookup.data.IstexData;
import com.scienceminer.lookup.data.MatchingDocument;
import com.scienceminer.lookup.data.PmidData;
import com.scienceminer.lookup.exception.NotFoundException;
import com.scienceminer.lookup.storage.lookup.*;
import com.scienceminer.lookup.utils.grobid.GrobidClient;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import scala.Option;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.StringUtils.*;

public class LookupEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(LookupEngine.class);

    private OALookup oaDoiLookup = null;

    private IstexIdsLookup istexLookup = null;

    private MetadataLookup metadataLookup = null;
    private MetadataMatching metadataMatching = null;
    private PMIdsLookup pmidLookup = null;

    // DOI matching regex from GROBID
    public static Pattern DOIPattern = Pattern.compile("\"DOI\"\\s?:\\s?\"(10\\.\\d{4,5}\\/[^\"\\s]+[^;,.\\s])\"");
    
    private GrobidClient grobidClient = null;

    private static String ISTEX_BASE = "https://api.istex.fr/document/";

    public LookupEngine() {
    }

    public LookupEngine(StorageEnvFactory storageFactory) {
        this.oaDoiLookup = new OALookup(storageFactory);
        this.istexLookup = new IstexIdsLookup(storageFactory);
        this.metadataLookup = new MetadataLookup(storageFactory);
        this.metadataMatching = new MetadataMatching(storageFactory.getConfiguration(), metadataLookup);
        this.pmidLookup = new PMIdsLookup(storageFactory);
    }

    /**
     * Blocking by article title and first author name
     */
    public String retrieveByArticleMetadata(String atitle, 
                                            String firstAuthor, 
                                            Boolean postValidate) {
        List<MatchingDocument> matchingDocuments = metadataMatching.retrieveByMetadata(atitle, firstAuthor);
        List<MatchingDocument> rankedMatchingDocuments = pairwiseRanking(atitle, firstAuthor, matchingDocuments);

        if (postValidate != null && postValidate) {
            if (!areMetadataMatching(atitle, firstAuthor, rankedMatchingDocuments.get(0))) {
                throw new NotFoundException("Best bibliographical record did not passed the post-validation");
            }
        }
        return injectIdsByDoi(rankedMatchingDocuments.get(0).getJsonObject(), rankedMatchingDocuments.get(0).getDOI());
    }

    /**
     * Async blocking by article title and first author name
     */
    public void retrieveByArticleMetadataAsync(String atitle, 
                                               String firstAuthor, 
                                               Boolean postValidate, 
                                               Consumer<MatchingDocument> callback) {
        metadataMatching.retrieveByMetadataAsync(atitle, firstAuthor, matchingDocuments -> {
            if (matchingDocuments == null || matchingDocuments.size() == 0) {
                callback.accept(new MatchingDocument(new NotFoundException("No matching document found")));
                return;
            }

            MatchingDocument resultDocument = matchingDocuments.get(0);
            if (!resultDocument.isException()) {

                List<MatchingDocument> rankedMatchingDocuments = pairwiseRanking(atitle, firstAuthor, matchingDocuments);

                if (postValidate != null && postValidate) {
                    if (!areMetadataMatching(atitle, firstAuthor, rankedMatchingDocuments.get(0))) {
                        callback.accept(new MatchingDocument(new NotFoundException("Best bibliographical record did not passed the post-validation")));
                        return;
                    }
                }

                resultDocument = rankedMatchingDocuments.get(0);
                final String s = injectIdsByDoi(resultDocument.getJsonObject(), resultDocument.getDOI());
                resultDocument.setFinalJsonObject(s);
            }
            callback.accept(resultDocument);
        });
    }

    /**
     * Blocking by journal title, volume and first page
     */
    public void retrieveByJournalMetadataAsync(String jtitle, 
                                               String volume, 
                                               String firstPage,
                                               String atitle, 
                                               String firstAuthor, 
                                               Boolean postValidate, 
                                               Consumer<MatchingDocument> callback) {
        metadataMatching.retrieveByMetadataAsync(jtitle, volume, firstPage, firstAuthor, matchingDocuments -> {
            if (matchingDocuments == null || matchingDocuments.size() == 0) {
                callback.accept(new MatchingDocument(new NotFoundException("No matching document found")));
                return;
            }

            MatchingDocument resultDocument = matchingDocuments.get(0);
            if (!resultDocument.isException()) {
                
                List<MatchingDocument> rankedMatchingDocuments = pairwiseRanking(atitle, firstAuthor, jtitle, 
                    null, null, volume, null, firstPage, null, matchingDocuments);

                if (postValidate != null && postValidate) {
                    if (!areMetadataMatching(atitle, firstAuthor, rankedMatchingDocuments.get(0), true)) {
                        callback.accept(new MatchingDocument(new NotFoundException("Best bibliographical record did not passed the post-validation")));
                        return;
                    }
                }

                resultDocument = rankedMatchingDocuments.get(0);
                final String s = injectIdsByDoi(resultDocument.getJsonObject(), resultDocument.getDOI());
                resultDocument.setFinalJsonObject(s);
            }
            callback.accept(resultDocument);
        });
    }

    /**
     * Async blocking by journal title, volume and first page
     */
    /*public void retrieveByJournalMetadataAsync(String jtitle, 
                                               String volume, 
                                               String firstPage, 
                                               Consumer<MatchingDocument> callback) {
        metadataMatching.retrieveByMetadataAsync(jtitle, volume, firstPage, matchingDocuments -> {
            if (matchingDocuments == null || matchingDocuments.size() == 0) {
                callback.accept(new MatchingDocument(new NotFoundException("No matching document found")));
                return;
            }

            MatchingDocument resultDocument = matchingDocuments.get(0);
            if (!resultDocument.isException()) {
                
                List<MatchingDocument> rankedMatchingDocuments = pairwiseRanking(null, null, jtitle, 
                    null, null, volume, null, firstPage, null, matchingDocuments);

                resultDocument = rankedMatchingDocuments.get(0);

                final String s = injectIdsByDoi(resultDocument.getJsonObject(), resultDocument.getDOI());
                resultDocument.setFinalJsonObject(s);
            }
            callback.accept(resultDocument);
        });
    }*/

    /**
     * Async blocking by journal title, volume, first page and first author. 
     * first author might be null, and then ignored when blocking. 
     */
    public void retrieveByJournalMetadataAsync(String jtitle, 
                                               String volume, 
                                               String firstPage, 
                                               String firstAuthor,
                                               Consumer<MatchingDocument> callback) {
        metadataMatching.retrieveByMetadataAsync(jtitle, volume, firstPage, firstAuthor, matchingDocuments -> {

            if (matchingDocuments == null || matchingDocuments.size() == 0) {
                callback.accept(new MatchingDocument(new NotFoundException("No matching document found")));
                return;
            }

            MatchingDocument resultDocument = matchingDocuments.get(0);
            if (!resultDocument.isException()) {
                List<MatchingDocument> rankedMatchingDocuments = pairwiseRanking(null, firstAuthor, jtitle, 
                    null, null, volume, null, firstPage, null, matchingDocuments);

                resultDocument = rankedMatchingDocuments.get(0);

                final String s = injectIdsByDoi(resultDocument.getJsonObject(), resultDocument.getDOI());
                resultDocument.setFinalJsonObject(s);
            }
            callback.accept(resultDocument);
        });
    }

    public String retrieveByDoi(String doi, 
                                Boolean postValidate, 
                                String firstAuthor, 
                                String atitle) {
        MatchingDocument outputData = metadataLookup.retrieveByMetadata(doi);
        outputData = validateJsonBody(postValidate, firstAuthor, atitle, outputData);

        return injectIdsByDoi(outputData.getJsonObject(), outputData.getDOI());
    }

    private MatchingDocument validateJsonBody(Boolean postValidate, String firstAuthor, String atitle, MatchingDocument outputData) {
        if (isBlank(outputData.getJsonObject())) {
            throw new NotFoundException("No bibliographical record found");
        }

        if (postValidate != null && postValidate && isNotBlank(firstAuthor)) {
            outputData = extractTitleAndFirstAuthorFromJson(outputData);

            if (!areMetadataMatching(atitle, firstAuthor, outputData, true)) {
                throw new NotFoundException("Best bibliographical record did not passed the post-validation");
            }
        }
        return outputData;
    }

    private MatchingDocument extractTitleAndFirstAuthorFromJson(MatchingDocument outputData) {
        JsonElement jelement = new JsonParser().parse(outputData.getJsonObject());
        JsonObject jobject = jelement.getAsJsonObject();

        final JsonArray titlesFromJson = jobject.get("title").getAsJsonArray();
        if (titlesFromJson != null && titlesFromJson.size() > 0) {
            String titleFromJson = titlesFromJson.get(0).getAsString();
            outputData.setATitle(titleFromJson);
        }

        final JsonArray authorsFromJson = jobject.get("author").getAsJsonArray();
        if (authorsFromJson != null && authorsFromJson.size() > 0) {

            String firstAuthorFromJson = "";
            for (int i = 0; i < authorsFromJson.size(); i++) {
                final JsonObject currentAuthor = authorsFromJson.get(i).getAsJsonObject();
                if (currentAuthor.has("sequence")
                        && StringUtils.equals(currentAuthor.get("sequence").getAsString(), "first")) {
                    firstAuthorFromJson = currentAuthor.get("family").getAsString();
                    outputData.setFirstAuthor(firstAuthorFromJson);
                    break;
                }
            }
        }

        return outputData;
    }

     private MatchingDocument extractMetadataFromJson(MatchingDocument outputData) {
        JsonElement jelement = new JsonParser().parse(outputData.getJsonObject());
        JsonObject jobject = jelement.getAsJsonObject();

        final JsonArray titlesFromJson = jobject.get("title").getAsJsonArray();
        if (titlesFromJson != null && titlesFromJson.size() > 0) {
            String titleFromJson = titlesFromJson.get(0).getAsString();
            outputData.setATitle(titleFromJson);
        }

        final JsonArray authorsFromJson = jobject.get("author").getAsJsonArray();
        if (authorsFromJson != null && authorsFromJson.size() > 0) {

            String firstAuthorFromJson = "";
            for (int i = 0; i < authorsFromJson.size(); i++) {
                final JsonObject currentAuthor = authorsFromJson.get(i).getAsJsonObject();
                if (currentAuthor.has("sequence")
                        && StringUtils.equals(currentAuthor.get("sequence").getAsString(), "first")) {
                    // this supposes we always have a family name (not raw full name by default?)
                    firstAuthorFromJson = currentAuthor.get("family").getAsString();
                    outputData.setFirstAuthor(firstAuthorFromJson);
                    break;
                }
            }

            if (outputData.getFirstAuthor() == null) {
                // no sequence information available, as fallback we take the first authot in the list
                final JsonObject currentAuthor = authorsFromJson.get(0).getAsJsonObject();
                // this supposes we always have a family name (not raw full name by default?)
                firstAuthorFromJson = currentAuthor.get("family").getAsString();
                outputData.setFirstAuthor(firstAuthorFromJson);
            }
        }

        // other metadata relevant for pairwise matching to be extracted here


        return outputData;
    }

    public String retrieveByPmid(String pmid, Boolean postValidate, String firstAuthor, String atitle) {
        final PmidData pmidData = pmidLookup.retrieveIdsByPmid(pmid);

        if (pmidData != null && isNotBlank(pmidData.getDoi())) {
            return retrieveByDoi(pmidData.getDoi(), postValidate, firstAuthor, atitle);
        }

        throw new NotFoundException("Cannot find bibliographical record with PMID " + pmid);
    }

    public String retrieveByPmc(String pmc, Boolean postValidate, String firstAuthor, String atitle) {
        if (!StringUtils.startsWithIgnoreCase(pmc, "pmc")) {
            pmc = "PMC" + pmc;
        }

        final PmidData pmidData = pmidLookup.retrieveIdsByPmc(pmc);

        if (pmidData != null && isNotBlank(pmidData.getDoi())) {
            return retrieveByDoi(pmidData.getDoi(), postValidate, firstAuthor, atitle);
        }

        throw new NotFoundException("Cannot find bibliographical record with PMC ID " + pmc);
    }

    public String retrieveByIstexid(String istexid, Boolean postValidate, String firstAuthor, String atitle) {
        final IstexData istexData = istexLookup.retrieveByIstexId(istexid);

        if (istexData != null && CollectionUtils.isNotEmpty(istexData.getDoi()) && isNotBlank(istexData.getDoi().get(0))) {
            final String doi = istexData.getDoi().get(0);
            MatchingDocument outputData = metadataLookup.retrieveByMetadata(doi);

            outputData = validateJsonBody(postValidate, firstAuthor, atitle, outputData);

            final String oaLink = oaDoiLookup.retrieveOaLinkByDoi(doi);
            return injectIdsAndOALink(outputData.getJsonObject(), doi, istexData, oaLink);
        }

        throw new NotFoundException("Cannot find bibliographical record with ISTEX ID " + istexid);
    }

    public String retrieveByPii(String pii, Boolean postValidate, String firstAuthor, String atitle) {
        final IstexData istexData = istexLookup.retrieveByPii(pii);

        if (istexData != null && CollectionUtils.isNotEmpty(istexData.getDoi()) && isNotBlank(istexData.getDoi().get(0))) {
            final String doi = istexData.getDoi().get(0);
            MatchingDocument outputData = metadataLookup.retrieveByMetadata(doi);

            outputData = validateJsonBody(postValidate, firstAuthor, atitle, outputData);

            final String oaLink = oaDoiLookup.retrieveOaLinkByDoi(doi);
            return injectIdsAndOALink(outputData.getJsonObject(), doi, istexData, oaLink);
        }

        throw new NotFoundException("Cannot find bibliographical record by PII " + pii);
    }

    // Intermediate lookups

    public PmidData retrievePMidsByDoi(String doi) {
        return pmidLookup.retrieveIdsByDoi(doi);
    }

    public PmidData retrievePMidsByPmid(String pmid) {
        return pmidLookup.retrieveIdsByPmid(pmid);
    }

    public PmidData retrievePMidsByPmc(String pmc) {
        return pmidLookup.retrieveIdsByPmc(pmc);
    }

    public IstexData retrieveIstexIdsByDoi(String doi) {
        return istexLookup.retrieveByDoi(doi);
    }

    public IstexData retrieveIstexIdsByIstexId(String istexId) {
        return istexLookup.retrieveByIstexId(istexId);
    }

    public String retrieveOAUrlByDoi(String doi) {

        final String output = oaDoiLookup.retrieveOaLinkByDoi(doi);

        if (isBlank(output)) {
            throw new NotFoundException("Open Access URL was not found for DOI " + doi);
        }

        return output;
    }

    public Pair<String,String> retrieveOaIstexUrlByDoi(String doi) {

        final String oaLink = oaDoiLookup.retrieveOaLinkByDoi(doi);
        final IstexData istexRecord = istexLookup.retrieveByDoi(doi);
        String url = null;

        if (isBlank(oaLink) && istexRecord == null) {
            throw new NotFoundException("Open Access and Istex URL were not found for DOI " + doi);
        }        

        if (istexRecord != null) {
            String istexId = istexRecord.getIstexId();
            url = ISTEX_BASE + istexId + "/fulltext/pdf";
        }

        return Pair.of(oaLink, url);
    }

    public String retrieveOAUrlByPmid(String pmid) {
        final PmidData pmidData = pmidLookup.retrieveIdsByPmid(pmid);

        if (pmidData != null && isNotBlank(pmidData.getDoi())) {
            return oaDoiLookup.retrieveOaLinkByDoi(pmidData.getDoi());
        }

        throw new NotFoundException("Open Access URL was not found for PM ID " + pmid);
    }

    public Pair<String,String> retrieveOaIstexUrlByPmid(String pmid) {

        final PmidData pmidData = pmidLookup.retrieveIdsByPmid(pmid);

        if (pmidData == null || isBlank(pmidData.getDoi())) {
            throw new NotFoundException("Open Access and Istex URL were not found for PMID " + pmid);
        }        

        final String oaLink = oaDoiLookup.retrieveOaLinkByDoi(pmidData.getDoi());
        final IstexData istexRecord = istexLookup.retrieveByDoi(pmidData.getDoi());
        String url = null;

        if (isBlank(oaLink) && istexRecord == null) {
            throw new NotFoundException("Open Access and Istex URL were not found for PMID " + pmid);
        }        

        if (istexRecord != null) {
            String istexId = istexRecord.getIstexId();
            url = ISTEX_BASE + istexId + "/fulltext/pdf";
        }

        return Pair.of(oaLink, url);
    }

    public String retrieveOAUrlByPmc(String pmc) {
        final PmidData pmidData = pmidLookup.retrieveIdsByPmc(pmc);

        if (pmidData != null && isNotBlank(pmidData.getDoi())) {
            return oaDoiLookup.retrieveOaLinkByDoi(pmidData.getDoi());
        }

        throw new NotFoundException("Open Access URL was not found for PMC ID " + pmc);
    }

    public Pair<String,String> retrieveOaIstexUrlByPmc(String pmc) {

        final PmidData pmidData = pmidLookup.retrieveIdsByPmc(pmc);

        if (pmidData == null || isBlank(pmidData.getDoi())) {
            throw new NotFoundException("Open Access and Istex URL were not found for PMC " + pmc);
        }        

        final String oaLink = oaDoiLookup.retrieveOaLinkByDoi(pmidData.getDoi());
        final IstexData istexRecord = istexLookup.retrieveByDoi(pmidData.getDoi());
        String url = null;

        if (isBlank(oaLink) && istexRecord == null) {
            throw new NotFoundException("Open Access and Istex URL were not found for PMC " + pmc);
        }        

        if (istexRecord != null) {
            String istexId = istexRecord.getIstexId();
            url = ISTEX_BASE + istexId + "/fulltext/pdf";
        }

        return Pair.of(oaLink, url);
    }

    public String retrieveOAUrlByPii(String pii) {
        final IstexData istexData = istexLookup.retrieveByPii(pii);

        if (istexData != null && CollectionUtils.isNotEmpty(istexData.getDoi())) {
            // TBD: we might want to iterate to several DOI
            return oaDoiLookup.retrieveOaLinkByDoi(istexData.getDoi().get(0));
        }

        throw new NotFoundException("Open Access URL was not found for pii " + pii);
    }

    public Pair<String,String> retrieveOaIstexUrlByPii(String pii) {

        final IstexData istexData = istexLookup.retrieveByPii(pii);

        if (istexData == null || istexData.getDoi() == null || istexData.getDoi().size() == 0) {
            throw new NotFoundException("Open Access and Istex URL were not found for PII " + pii);
        }        

        // TBD: we might want to iterate to several DOI
        final String oaLink = oaDoiLookup.retrieveOaLinkByDoi(istexData.getDoi().get(0));
        String istexId = istexData.getIstexId();
        String url = ISTEX_BASE + istexId + "/fulltext/pdf";

        return Pair.of(oaLink, url);
    }

    /**
     * Async blocking by full bibliographical reference string, with Grobid parsing for pairwise matching
     */
    public void retrieveByBiblioAsync(String biblio, 
                                      Boolean postValidate, 
                                      final String firstAuthor, 
                                      final String atitle, 
                                      Boolean parseReference, 
                                      Consumer<MatchingDocument> callback) {
        metadataMatching.retrieveByBiblioAsync(biblio, matchingDocuments -> {
            if (matchingDocuments == null || matchingDocuments.size() == 0) {
                callback.accept(new MatchingDocument(new NotFoundException("No matching document found")));
                return;
            }

            MatchingDocument resultDocument = matchingDocuments.get(0);
            if (!resultDocument.isException()) {
                //if ((isBlank(firstAuthor) || isBlank(atitle)) && parseReference) {
                if (isBlank(firstAuthor) && parseReference) {
                    try {
                        grobidClient.ping();
                        grobidClient.processCitation(biblio, "0", response -> {

                            // TBD: extract more metadata from Grobid result to improve the pairwise ranking

                            String firstAuthor1 = 
                                isNotBlank(response.getFirstAuthor()) ? response.getFirstAuthor() : response.getFirstAuthorMonograph();
                            String atitle1 = response.getAtitle();

                            if (isBlank(firstAuthor1))
                                firstAuthor1 = firstAuthor;
                            if (isBlank(atitle1))
                                atitle1 = atitle;

                            List<MatchingDocument> rankedMatchingDocuments = pairwiseRanking(atitle1, firstAuthor1, matchingDocuments);
                            final MatchingDocument localResultDocument = rankedMatchingDocuments.get(0);

                            if (postValidate != null && postValidate) {
                                //no title and author, extract with grobid. if grobid unavailable... it will fail.
                                if (!isBlank(firstAuthor1)) {

                                    if (!areMetadataMatching(atitle1, firstAuthor1, localResultDocument, true)) {
                                        callback.accept(new MatchingDocument(new NotFoundException("Best bibliographical record did not passed the post-validation")));
                                        return;
                                    }
                                    final String s = injectIdsByDoi(localResultDocument.getJsonObject(), localResultDocument.getDOI());
                                    localResultDocument.setFinalJsonObject(s);
                                    callback.accept(localResultDocument);
                                }
                            }
                        });
                    } catch (Exception e) {
                        LOGGER.warn("GROBID not available, no extra metadata available for pairwise ranking");
                    }
                }

                // pairwise ranking with whatever is available
                List<MatchingDocument> rankedMatchingDocuments = pairwiseRanking(atitle, firstAuthor, matchingDocuments);
                final MatchingDocument localResultDocument = rankedMatchingDocuments.get(0);

                if (postValidate != null && postValidate) {
                    if (!isBlank(firstAuthor)) {

                        if (!areMetadataMatching(atitle, firstAuthor, localResultDocument, true)) {
                            callback.accept(new MatchingDocument(new NotFoundException("Best bibliographical record did not passed the post-validation")));
                            return;
                        }
                        final String s = injectIdsByDoi(localResultDocument.getJsonObject(), localResultDocument.getDOI());
                        localResultDocument.setFinalJsonObject(s);
                        callback.accept(localResultDocument);
                    } else {
                        // we cannot post validate
                        callback.accept(new MatchingDocument(new NotFoundException("No metadata available for post-validation")));
                        return;
                    }
                } else {
                    final String s = injectIdsByDoi(localResultDocument.getJsonObject(), localResultDocument.getDOI());
                    localResultDocument.setFinalJsonObject(s);
                    callback.accept(localResultDocument);
                }

                resultDocument = localResultDocument;
            } 

            callback.accept(resultDocument);
        });
    }

    /**
     * Async blocking by full bibliographical reference string, without Grobid for pairwise matching
     */
    public void retrieveByBiblioAsync(String biblio, 
                                      Consumer<MatchingDocument> callback) {
        metadataMatching.retrieveByBiblioAsync(biblio, matchingDocuments -> {
            if (matchingDocuments == null || matchingDocuments.size() == 0) {
                callback.accept(new MatchingDocument(new NotFoundException("No matching document found")));
                return;
            }

            MatchingDocument resultDocument = matchingDocuments.get(0);
            if (!resultDocument.isException()) {
                final String s = injectIdsByDoi(resultDocument.getJsonObject(), resultDocument.getDOI());
                resultDocument.setFinalJsonObject(s);
            }
            callback.accept(resultDocument);
        });
    }

    public String extractDOI(String input) {

        Matcher doiMatcher = DOIPattern.matcher(input);
        while (doiMatcher.find()) {
            if (doiMatcher.groupCount() == 1) {
                return doiMatcher.group(1);
            }
        }

        return null;
    }

    private List<MatchingDocument> pairwiseRanking(String atitle, 
                                           String firstAuthor,
                                           List<MatchingDocument> matchingDocuments) {
        return pairwiseRanking(atitle, firstAuthor, null, null, null, null, null, null, null, matchingDocuments);
    }

    private List<MatchingDocument> pairwiseRanking(String atitle, 
                                           String firstAuthor, 
                                           String jtitle,
                                           String btitle, 
                                           String year, 
                                           String volume, 
                                           String issue, 
                                           String firstPage,
                                           List<String> authors,
                                           List<MatchingDocument> matchingDocuments) {
        List<MatchingDocument> rankedMatchingDocuments = matchingDocuments;

        MatchingDocument referenceDocument = new MatchingDocument();
        referenceDocument.setATitle(atitle);
        referenceDocument.setFirstAuthor(firstAuthor);
        referenceDocument.setJTitle(jtitle);
        referenceDocument.setBTitle(btitle);
        referenceDocument.setYear(year);
        referenceDocument.setVolume(volume);
        referenceDocument.setIssue(issue);
        referenceDocument.setFirstPage(firstPage);
        referenceDocument.setAuthors(authors);

        for(MatchingDocument matchingDocument : matchingDocuments) {
            matchingDocument = extractMetadataFromJson(matchingDocument);

            double computedRecordDistance = recordDistance(matchingDocument, referenceDocument);
            // here we can introduce a threshold when removing the post validation
            matchingDocument.setMatchingScore(computedRecordDistance);
            rankedMatchingDocuments.add(matchingDocument);
        }

        Collections.sort(rankedMatchingDocuments, new Comparator<MatchingDocument>() {
            @Override
            public int compare(MatchingDocument d1, MatchingDocument d2) {
                double diff = d2.getMatchingScore() - d1.getMatchingScore();
                if (diff < 0.0) {
                    return -1;
                } else if (diff > 0.0) {
                    return 1;
                }
                return 0;
            }
        });

        return matchingDocuments;
    }

    /**
     * Compute a distance score between a candidate matching document and a reference document with target
     * metadata. score is in [0,1], with 1 perfect match
     */
    private double recordDistance(MatchingDocument matchingDocument, MatchingDocument referenceDocument) {
        // atitle component
        Double atitleScore = 0.0;
        if (isNotBlank(matchingDocument.getATitle()) && isNotBlank(referenceDocument.getATitle())) {
            atitleScore = ratcliffObershelpDistance(referenceDocument.getATitle(), matchingDocument.getATitle(), false);
        }

        // first author component
        Double firstAuthorScore = 0.0;
        if (isNotBlank(matchingDocument.getFirstAuthor()) && isNotBlank(referenceDocument.getFirstAuthor())) {
            firstAuthorScore = ratcliffObershelpDistance(referenceDocument.getFirstAuthor(), matchingDocument.getFirstAuthor(), false);
        } 

        // more metadata
        // TBD

        // manage strong clash: if key fields are totally different, we lower the score down to 0
        // TBD

        // TBD: we can use a more robust mean, weights, but ideally we should use a classifier
        double score = (atitleScore + firstAuthorScore) / 2;

        // ...

        return score;
    }

    /**
     * Methods borrowed from GROBID Consolidation.java
     */

    /**
     * Introduce a minimum matching threshold for key metadata.
     */
    private boolean areMetadataMatching(String atitle, String firstAuthor, MatchingDocument result, boolean ignoreTitleIfNotPresent) {
        boolean valid = true;


        if (isNotBlank(atitle)) {
            if (ratcliffObershelpDistance(atitle, result.getATitle(), false) < 0.8)
                return false;
        } else if (!ignoreTitleIfNotPresent) {
            return false;
        }

        if (ratcliffObershelpDistance(firstAuthor, result.getFirstAuthor(), false) < 0.8)
            return false;

        return valid;
    }

    /**
     * default version checking title and authors
     **/
    private boolean areMetadataMatching(String atitle, String firstAuthor, MatchingDocument result) {
        return areMetadataMatching(atitle, firstAuthor, result, false);
    }

    private double ratcliffObershelpDistance(String string1, String string2, boolean caseDependent) {
        if (StringUtils.isBlank(string1) || StringUtils.isBlank(string2))
            return 0.0;
        Double similarity = 0.0;

        if (!caseDependent) {
            string1 = string1.toLowerCase();
            string2 = string2.toLowerCase();
        }

        if (string1.equals(string2))
            similarity = 1.0;
        if ((string1.length() > 0) && (string2.length() > 0)) {
            Option<Object> similarityObject =
                    RatcliffObershelpMetric.compare(string1, string2);
            if ((similarityObject != null) && (similarityObject.get() != null))
                similarity = (Double) similarityObject.get();
        }

        return similarity;
    }

    protected String injectIdsByDoi(String jsonobj, String doi) {
        final IstexData istexData = istexLookup.retrieveByDoi(doi);

        final String oaLink = oaDoiLookup.retrieveOaLinkByDoi(doi);

        return injectIdsAndOALink(jsonobj, doi, istexData, oaLink);
    }

    protected String injectIdsAndOALink(String jsonobj, String doi, IstexData istexData, String oaLink) {
        boolean pmid = false;
        boolean pmc = false;
        boolean foundIstexData = false;
        boolean foundPmidData = false;
        boolean first = false;
        boolean foundOaLink = false;

        StringBuilder sb = new StringBuilder();
        if (isBlank(jsonobj)) {
            sb.append("{");
            first = true;
        } else {
            sb.append(jsonobj, 0, length(jsonobj) - 1);
        }

        if (istexData != null) {
            if (isNotBlank(istexData.getIstexId())) {
                if (!first) {
                    sb.append(", ");
                } else {
                    first = false;
                }
                sb.append("\"istexId\":\"" + istexData.getIstexId() + "\"");
                foundIstexData = true;
            }
            if (CollectionUtils.isNotEmpty(istexData.getArk())) {
                if (!first) {
                    sb.append(", ");
                } else {
                    first = false;
                }
                sb.append("\"ark\":\"" + istexData.getArk().get(0) + "\"");
                foundIstexData = true;
            }
            if (CollectionUtils.isNotEmpty(istexData.getPmid())) {
                if (!first) {
                    sb.append(", ");
                } else {
                    first = false;
                }
                sb.append("\"pmid\":\"" + istexData.getPmid().get(0) + "\"");
                pmid = true;
                foundIstexData = true;
            }
            if (CollectionUtils.isNotEmpty(istexData.getPmc())) {
                if (!first) {
                    sb.append(", ");
                } else {
                    first = false;
                }
                sb.append("\"pmcid\":\"" + istexData.getPmc().get(0) + "\"");
                pmc = true;
                foundIstexData = true;
            }
            if (CollectionUtils.isNotEmpty(istexData.getMesh())) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("\"mesh\":\"" + istexData.getMesh().get(0) + "\"");
                foundIstexData = true;
            }
            if (CollectionUtils.isNotEmpty(istexData.getPii())) {
                if (!first) {
                    sb.append(", ");
                } else {
                    first = false;
                }
                sb.append("\"pii\":\"" + istexData.getPii().get(0) + "\"");
                foundIstexData = true;
            }
        }

        if (!pmid || !pmc) {
            final PmidData pmidData = pmidLookup.retrieveIdsByDoi(doi);
            if (pmidData != null) {
                if (isNotBlank(pmidData.getPmid()) && !pmid) {
                    if (!first) {
                        sb.append(", ");
                    } else {
                        first = false;
                    }
                    sb.append("\"pmid\":\"" + pmidData.getPmid() + "\"");
                    foundPmidData = true;
                }

                if (isNotBlank(pmidData.getPmcid()) && !pmc) {
                    if (!first) {
                        sb.append(", ");
                    } else {
                        first = false;
                    }
                    sb.append("\"pmcid\":\"" + pmidData.getPmcid() + "\"");
                    foundPmidData = true;
                }
            }
        }

        if (isNotBlank(oaLink)) {
            if (!first) {
                sb.append(", ");
            } else {
                first = false;
            }
            sb.append("\"oaLink\":\"" + oaLink + "\"");
            foundOaLink = true;
        }

        if (foundIstexData || foundPmidData || foundOaLink) {
            sb.append("}");
            return sb.toString();
        } else {
            return jsonobj;
        }
    }

    public void setMetadataMatching(MetadataMatching metadataMatching) {
        this.metadataMatching = metadataMatching;
    }

    public void setOaDoiLookup(OALookup oaDoiLookup) {
        this.oaDoiLookup = oaDoiLookup;
    }

    public void setIstexLookup(IstexIdsLookup istexLookup) {
        this.istexLookup = istexLookup;
    }

    public void setMetadataLookup(MetadataLookup metadataLookup) {
        this.metadataLookup = metadataLookup;
    }

    public void setPmidLookup(PMIdsLookup pmidLookup) {
        this.pmidLookup = pmidLookup;
    }

    public void setGrobidClient(GrobidClient grobidClient) {
        this.grobidClient = grobidClient;
    }
}
