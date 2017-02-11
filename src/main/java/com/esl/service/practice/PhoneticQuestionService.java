package com.esl.service.practice;

import com.esl.dao.IPhoneticQuestionDAO;
import com.esl.dao.IVocabImageDAO;
import com.esl.entity.VocabImage;
import com.esl.entity.rest.DictionaryResult;
import com.esl.entity.rest.WebItem;
import com.esl.model.PhoneticQuestion;
import com.esl.service.rest.WebParserRestService;
import org.apache.commons.codec.binary.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class PhoneticQuestionService {
    private static Logger log = LoggerFactory.getLogger(PhoneticQuestionService.class);

    @Resource private IVocabImageDAO vocabImageDao;
    @Resource private IPhoneticQuestionDAO phoneticQuestionDAO;
    @Resource private WebParserRestService webService;
    @Resource private RestTemplate restTemplate;

    public Optional<PhoneticQuestion> getQuestionFromDBWithImage(String word) {
        Optional<PhoneticQuestion> question = Optional.ofNullable(phoneticQuestionDAO.getPhoneticQuestionByWord(word));
        question.ifPresent(this::enrichVocabImageFromDB);
        return question;
    }

    public PhoneticQuestion buildQuestion(String word) {
        log.info("buildQuestion for word: {}", word);
        PhoneticQuestion question = new PhoneticQuestion();
        question.setWord(word);

        CompletableFuture<WebItem[]> imagesResult = webService.searchGoogleImage(word);
        CompletableFuture<Optional<DictionaryResult>> dictionaryResult = webService.queryDictionary(word);

        fillQuestionByDictionaryResult(question, dictionaryResult.join());
        setPicsFullPaths(question, imagesResult.join());

        return question;
    }

    private void setPicsFullPaths(PhoneticQuestion question, WebItem[] items) {
        List<String> images = Arrays.stream(items)
                .map(i -> i.url)
                .limit(5)
                .collect(Collectors.toList());

        question.setPicsFullPaths(images.toArray(new String[images.size()]));
    }

    private void fillQuestionByDictionaryResult(PhoneticQuestion question, Optional<DictionaryResult> r) {
        if (!r.isPresent()) {
            question.setIPAUnavailable(true);
        } else {
            DictionaryResult result = r.get();
            if (org.apache.commons.lang3.StringUtils.isNotBlank(result.IPA))
                question.setIPA(result.IPA);
            else
                question.setIPAUnavailable(true);
            question.setPronouncedLink(result.pronunciationUrl);
        }
    }

    public void enrichIfNeeded(List<PhoneticQuestion> questions) {
        log.debug("enrich: {} questions", questions.size());

        questions.stream()
                .filter(q -> !getVocabImagesFromDB(q))
                .forEach(this::getAndStoreImagesFromWeb);
    }

    private PhoneticQuestion getAndStoreImagesFromWeb(PhoneticQuestion question) {
        log.debug("getAndStoreImagesFromWeb for {}", question.getWord());

        try {
            WebItem[] items = webService.searchGoogleImage(question.getWord() + " clipart").join();
            List<String> images = Arrays.stream(items)
                    .map(i -> i.url)
                    .filter(url -> !url.endsWith("svg"))
                    .map(this::retrieveImageToString)
                    .filter(Optional::isPresent)
                    .limit(5)
                    .map(Optional::get)
                    .map(imageStr -> persistImage(imageStr, question.getWord()))
                    .collect(Collectors.toList());

            question.setPicsFullPaths(images.toArray(new String[1]));
        } catch (Exception e) {
            log.error("Cannot get images from web for {}", question.getWord(), e);
        }
        return question;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private String persistImage(String imageAsStr, String word) {
        vocabImageDao.persist(new VocabImage(word, imageAsStr));
        vocabImageDao.flush();
        return imageAsStr;
    }


    private Optional<String> retrieveImageToString(String url) {
        log.debug("retrieveImageToString from url: {}", url);
        try {
            String extension = url.substring(url.lastIndexOf('.') + 1);
            byte[] byteArray = restTemplate.getForObject(url, byte[].class);
            return Optional.of("data:image/" + extension + ";base64," + StringUtils.newStringUtf8(org.apache.commons.codec.binary.Base64.encodeBase64(byteArray, false)));
        } catch (Exception e) {
            log.error("Cannot retrieve image from url: {}", url, e);
            return Optional.empty();
        }

    }

    @Transactional(readOnly = true)
    private boolean getVocabImagesFromDB(PhoneticQuestion question) {
        List<VocabImage> images = vocabImageDao.listByWord(question.getWord());
        if (images.size() < 1) return false;

        String[] imagesArr = images.stream()
                                .map(VocabImage::getBase64Image)
                                .collect(Collectors.toList())
                                .toArray(new String[0]);
        log.debug("get {} images for {} ", images.size(), question.getWord());
        return true;
    }

    @Transactional(readOnly = true)
    public void enrichVocabImageFromDB(List<PhoneticQuestion> questions) {
        questions.forEach(this::enrichVocabImageFromDB);
    }

    @Transactional(readOnly = true)
    public void enrichVocabImageFromDB(PhoneticQuestion question) {
        List<String> images = vocabImageDao.listByWord(question.getWord()).stream()
                .map(VocabImage::getBase64Image)
                .collect(Collectors.toList());

        if (images.size() > 0) {
            Collections.shuffle(images);
            question.setPicsFullPaths(images.toArray(new String[images.size()]));
        }
    }
}
