package com.esl.web.jsf.controller.practice;

import com.esl.dao.IGradeDAO;
import com.esl.dao.IPhoneticQuestionDAO;
import com.esl.dao.IPracticeResultDAO;
import com.esl.entity.event.UpdatePracticeHistoryEvent;
import com.esl.enumeration.ESLPracticeType;
import com.esl.enumeration.VocabDifficulty;
import com.esl.model.Grade;
import com.esl.model.Member;
import com.esl.model.PhoneticQuestion;
import com.esl.model.PracticeResult;
import com.esl.model.practice.PhoneticSymbols;
import com.esl.service.practice.IPhoneticSymbolPracticeService;
import com.esl.service.practice.PhoneticQuestionService;
import com.esl.util.JSFUtil;
import com.esl.web.jsf.controller.ESLController;
import com.esl.web.model.practice.PhoneticQuestionHistory;
import com.esl.web.util.LanguageUtil;
import com.esl.web.util.SelectItemUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import reactor.bus.Event;
import reactor.bus.EventBus;

import javax.annotation.Resource;
import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import java.util.*;

@Controller
@Scope("session")
public class PhoneticSymbolPracticeController extends ESLController {
	public static int MAX_HISTORY = 10;

	private static Logger logger = LoggerFactory.getLogger(PhoneticSymbolPracticeController.class);
	private final String bundleName = "messages.practice.PhoneticSymbolPractice";
	private String inputView = "/practice/phoneticsymbolpractice/input";
	private String practiceView = "/practice/phoneticsymbolpractice/practice";
	private String resultView = "/practice/phoneticsymbolpractice/result";

	// for input page
	private List<SelectItem> levels;
	private PhoneticSymbols.Level selectedLevel;
	private String selectedGrade;
	private List<VocabDifficulty> allDifficulty = Arrays.asList(VocabDifficulty.values());
	private VocabDifficulty selectedDifficulty;

	// for practice page
	private String answer = "";
	private PracticeResult currentGradeResult;
	private PracticeResult allGradeResult;
	private Grade currentGrade;
	private Map<String, Boolean> selectionPhonics;

	private boolean isLevelUp = false;
	private PhoneticQuestion question;
	private List<PhoneticQuestionHistory> history;
	private int totalMark;
	private int totalFullMark;

	// Supporting classes
	@Resource private IGradeDAO gradeDAO;
	@Resource private IPhoneticSymbolPracticeService phoneticSymbolPracticeService;
	@Resource private IPracticeResultDAO practiceResultDAO;
	@Resource private IPhoneticQuestionDAO phoneticQuestionDAO;
	@Resource private PhoneticQuestionService phoneticQuestionService;
	@Autowired EventBus eventBus;

	@Value("${PhoneticPracticeG2.MaxHistory}")
	public void setMaxHistory(int max) {this.MAX_HISTORY = max; }

	// ============== Constructor ================//
	public PhoneticSymbolPracticeController() {
		totalFullMark = 0;
		history = new ArrayList<PhoneticQuestionHistory>();		
	}

	// ============== Functions ================//
	public String launchInput() {
		return inputView;
	}

	public String initCheck() {
		if (question == null)
			return inputView;
		else
			return "";
	}

	@Transactional
	public String start() {
		logger.info("start: selectedGrade: " + selectedGrade);
		if (selectedDifficulty == null) {
			logger.error("selectedDifficulty is null");
			return errorView;
		}

		clearController();
		getRandomQuestion();
		return practiceView;
	}

	@Transactional
	public String submitAnswer() {
		logger.info("submitAnswer: START");

		boolean isCorrect = phoneticSymbolPracticeService.checkAnswer(question, answer);
		updateHistory(isCorrect);
		if (isCorrect) totalMark++;

		answer = "";
		submitUpdatePracticeHistoryEvent(isCorrect);
		getRandomQuestion();

		return null;
	}

	private void updateHistory(boolean isCorrect) {
		PhoneticQuestionHistory h = prepareHistory(isCorrect);
		history.add(0, h);
		if (history.size() > MAX_HISTORY)
			history.remove(history.size() - 1);		// remove too many history
	}

	// process when completing the practice
	@Transactional
	public String completePractice() {
		logger.info("completePractice: START");

		if (userSession.isLogined()) {
			// retrieve ranking of the practiced grade
			Member member = userSession.getMember();
		}

		// reduce one mark for the undo question
		totalFullMark--;

		return JSFUtil.redirectToJSF(resultView);
	}

	//	 ============== Getter Functions ================//

	/**
	 * 	 Return grades available to the user
	 */
	public List<SelectItem> getAvailableGrades() {
		// return all grade if logined, otherwise only the first grade
		List<Grade> grades = gradeDAO.getAll();

		List<SelectItem> items = new ArrayList<SelectItem>(grades.size());
		for (int i=0; i< grades.size(); i++) {
			LanguageUtil.formatGradeDescription(grades.get(i), getLocale());
			SelectItem item = new SelectItem(grades.get(i).getTitle(), grades.get(i).getDescription());
			if (!userSession.isLogined() && i > 0) item.setDisabled(true);
			items.add(item);
		}
		logger.info("getAvailableGrades: returned items size: " + items.size());
		return items;
	}

	/**
	 * Use for jsp, To refresh all UI string to new language in result.jsp
	 */
	public String getInitResultLanguage() {
		logger.info("getInitResultLanguage: START");

		LanguageUtil.formatGradeDescription(currentGrade, getLocale()).getDescription();

		if (userSession.getMember() != null) LanguageUtil.formatGradeDescription(userSession.getMember().getGrade(), getLocale());
		return "";
	}

	public List<SelectItem> getLevels() { return SelectItemUtil.getPhoneticSymobolPracticeLevels(); }

	// ============== Supporting Functions ================//
	private void clearController() {
		answer = "";
		history.clear();
		isLevelUp = false;
		totalFullMark = 0;
		totalMark = 0;
		//memberWordController.setSavedQuestion(new HashMap<PhoneticQuestion, Boolean>());
	}

	private void getRandomQuestion() {
		question = phoneticQuestionDAO.getRandomQuestionWithinLength(selectedDifficulty.length, 1).get(0);
		logger.info("getRandomQuestion: a random question: word[{}]", question.getWord());

		phoneticQuestionService.enrichVocabImage(question);
		Set<String> phonics = phoneticSymbolPracticeService.getPhonicsListByLevel(selectedLevel, question.getIPA());
		selectionPhonics = new HashMap<>();
		for (String p : phonics) {
			selectionPhonics.put(p, Boolean.TRUE);
		}
		logger.info("start: selectionPhonics.size: {}", selectionPhonics.size());
		totalFullMark++;
	}

	private void setLevels() {
		logger.info("setLevels: START");
		FacesContext facesContext = FacesContext.getCurrentInstance();
		ResourceBundle bundle = ResourceBundle.getBundle(bundleName, facesContext.getViewRoot().getLocale());

		levels = new ArrayList<SelectItem>();

		for (PhoneticSymbols.Level l : PhoneticSymbols.Level.values()) {
			String desc = bundle.getString("level" + l.toString());
			SelectItem item = new SelectItem(l, desc);
			logger.info("setLevels: create new SelectItem [" + item + "]");
			levels.add(item);
		}
	}

	private PhoneticQuestionHistory prepareHistory(boolean isCorrect) {
		PhoneticQuestionHistory history = new PhoneticQuestionHistory();
		history.setAnswer(answer);
		history.setQuestion(question);
		if (isCorrect) {
			history.setCorrect(true);
		}
		return history;
	}

	private void submitUpdatePracticeHistoryEvent(boolean isCorrect) {
		if (!userSession.isLogined()) return;

		eventBus.notify("addHistory",
				Event.wrap(new UpdatePracticeHistoryEvent(userSession.getMember(),
						ESLPracticeType.PhoneticSymbolPractice,
						question,
						isCorrect,
						isCorrect ? phoneticSymbolPracticeService.calculateScore(selectedDifficulty, selectedLevel) : 0))
		);
	}

	//	 ============== Setter / Getter ================//

	public String getAnswer() {	return answer;	}
	public void setAnswer(String answer) {this.answer = answer;}

	public boolean isLevelUp() {return isLevelUp;}
	public void setLevelUp(boolean isLevelUp) {	this.isLevelUp = isLevelUp;}

	public PracticeResult getCurrentGradeResult() {	return currentGradeResult;	}
	public void setCurrentGradeResult(PracticeResult currentGradeResult) {	this.currentGradeResult = currentGradeResult;}

	public PhoneticQuestion getQuestion() {	return question;}
	public void setQuestion(PhoneticQuestion question) {this.question = question;}

	public Grade getCurrentGrade() {return currentGrade;}
	public void setCurrentGrade(Grade currentGrade) {this.currentGrade = currentGrade;}

	public int getTotalMark() {	return totalMark;}
	public void setTotalMark(int totalMark) {this.totalMark = totalMark;}

	public int getTotalFullMark() {	return totalFullMark;}
	public void setTotalFullMark(int totalFullMark) {this.totalFullMark = totalFullMark;}

	public List<PhoneticQuestionHistory> getHistory() {	return history;	}
	public void setHistory(List<PhoneticQuestionHistory> history) {	this.history = history;	}
	public int getHistorySize() { return history.size(); }

	public PhoneticSymbols.Level getSelectedLevel() {return selectedLevel;}
	public void setSelectedLevel(PhoneticSymbols.Level selectedLevel) {	this.selectedLevel = selectedLevel;}

	public String getSelectedGrade() {	return selectedGrade;}
	public void setSelectedGrade(String selectedGrade) {this.selectedGrade = selectedGrade;	}

	public List<VocabDifficulty> getAllDifficulty() {return allDifficulty;}
	public void setAllDifficulty(List<VocabDifficulty> allDifficulty) {	this.allDifficulty = allDifficulty; }

	public VocabDifficulty getSelectedDifficulty() {return selectedDifficulty;}
	public void setSelectedDifficulty(VocabDifficulty selectedDifficulty) {this.selectedDifficulty = selectedDifficulty;}

	public Map<String, Boolean> getSelectionPhonics() {return selectionPhonics;	}
	public void setSelectionPhonics( Map<String, Boolean> selectionPhonics) {this.selectionPhonics = selectionPhonics;}
	public int getTotalSelectionPhonics() {return selectionPhonics.size(); }

}

