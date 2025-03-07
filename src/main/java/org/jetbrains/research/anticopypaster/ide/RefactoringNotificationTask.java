package org.jetbrains.research.anticopypaster.ide;

import com.intellij.CommonBundle;
import com.intellij.notification.*;
import com.intellij.notification.impl.NotificationGroupManagerImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor;
import com.intellij.refactoring.extractMethod.PrepareFailedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.research.anticopypaster.AntiCopyPasterBundle;
import org.jetbrains.research.anticopypaster.checkers.FragmentCorrectnessChecker;
import org.jetbrains.research.extractMethod.metrics.MetricCalculator;
import org.jetbrains.research.anticopypaster.models.PredictionModel;
import org.jetbrains.research.extractMethod.metrics.features.FeaturesVector;

import javax.swing.event.HyperlinkEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.intellij.refactoring.extractMethod.ExtractMethodHandler.getProcessor;
import static org.jetbrains.research.anticopypaster.utils.PsiUtil.*;

/**
 * Shows a notification about discovered Extract Method refactoring opportunity.
 */
public class RefactoringNotificationTask extends TimerTask {
    private static final Double predictionThreshold = 0.2; // certainty threshold for models
    private ConcurrentLinkedQueue<RefactoringEvent> eventsQueue = new ConcurrentLinkedQueue<>();
    private static DuplicatesInspection inspection = new DuplicatesInspection();
    private final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup("Extract Method suggestion",
            NotificationDisplayType.BALLOON,
            true);

    private static final Logger LOG = Logger.getInstance(RefactoringNotificationTask.class);

    public RefactoringNotificationTask() {
    }

    @Override
    public void run() {
        while (!eventsQueue.isEmpty()) {
            try {
                final RefactoringEvent event = eventsQueue.poll();
                ApplicationManager.getApplication().runReadAction(() -> {
                    DuplicatesInspection.InspectionResult result = inspection.resolve(event.getFile(), event.getText());
                    if (result.getDuplicatesCount() < 2) {
                        return;
                    }

                    HashSet<String> variablesInCodeFragment = new HashSet<>();
                    HashMap<String, Integer> variablesCountsInCodeFragment = new HashMap<>();

                    if (!FragmentCorrectnessChecker.isCorrect(event.getProject(), event.getFile(),
                            event.getText(),
                            variablesInCodeFragment,
                            variablesCountsInCodeFragment)) {
                        return;
                    }

                    FeaturesVector featuresVector = calculateFeatures(event);

                    double prediction = PredictionModel.getClassificationValue(featuresVector);
                    event.setReasonToExtract(AntiCopyPasterBundle.message(
                            "extract.method.to.simplify.logic.of.enclosing.method")); // dummy

                    if ((event.isForceExtraction() || prediction > predictionThreshold) &&
                            canBeExtracted(event)) {
                        notify(event.getProject(),
                                AntiCopyPasterBundle.message(
                                        "extract.method.refactoring.is.available"),
                                getRunnableToShowSuggestionDialog(event)
                        );
                    }
                });
            } catch (Exception e) {
                LOG.error("[ACP] Can't process an event");
            }
        }
    }

    public boolean canBeExtracted(RefactoringEvent event) {
        boolean canBeExtracted;
        int startOffset = getStartOffset(event.getEditor(), event.getFile(), event.getText());
        PsiElement[] elementsInCodeFragment = getElements(event.getProject(), event.getFile(),
                startOffset, startOffset + event.getText().length());
        ExtractMethodProcessor processor = getProcessor(event.getProject(), elementsInCodeFragment,
                event.getFile(), false);
        if (processor == null) return false;
        try {
            canBeExtracted = processor.prepare(null);
            processor.findOccurrences();
        } catch (PrepareFailedException e) {
            LOG.error("[ACP] Failed to check if a code fragment can be extracted.", e.getMessage());
            return false;
        }

        return canBeExtracted;
    }

    private Runnable getRunnableToShowSuggestionDialog(RefactoringEvent event) {
        return () -> {
            String message = event.getReasonToExtract();
            if (message.isEmpty()) {
                message = AntiCopyPasterBundle.message("extract.method.to.simplify.logic.of.enclosing.method");
            }

            int startOffset = getStartOffset(event.getEditor(), event.getFile(), event.getText());
            event.getEditor().getSelectionModel().setSelection(startOffset, startOffset + event.getText().length());

            int result =
                    Messages.showOkCancelDialog(message,
                            AntiCopyPasterBundle.message("anticopypaster.recommendation.dialog.name"),
                            CommonBundle.getOkButtonText(),
                            CommonBundle.getCancelButtonText(),
                            Messages.getInformationIcon());

            //result is equal to 0 if a user accepted the suggestion and clicked on OK button, 1 otherwise
            if (result == 0) {
                scheduleExtraction(event.getProject(),
                        event.getFile(),
                        event.getEditor(),
                        event.getText());
            }
        };
    }

    public void notify(Project project, String content, Runnable callback) {
        final Notification notification = NOTIFICATION_GROUP.createNotification(content, NotificationType.INFORMATION);
        notification.setListener(new NotificationListener.Adapter() {
            @Override
            protected void hyperlinkActivated(@NotNull Notification notification, @NotNull HyperlinkEvent e) {
                callback.run();
            }
        });
        notification.notify(project);
    }

    private static void scheduleExtraction(Project project, PsiFile file, Editor editor, String text) {
        new java.util.Timer().schedule(
                new ExtractionTask(editor, file, text, project),
                100
        );
    }

    public void addEvent(RefactoringEvent event) {
        this.eventsQueue.add(event);
    }

    /**
     * Calculates the metrics for the pasted code fragment and a method where the code fragment was pasted into.
     */
    private FeaturesVector calculateFeatures(RefactoringEvent event) {
        PsiFile file = event.getFile();
        PsiMethod methodAfterPasting = event.getDestinationMethod();
        int eventBeginLine = getNumberOfLine(file,
                methodAfterPasting.getTextRange().getStartOffset());
        int eventEndLine = getNumberOfLine(file,
                methodAfterPasting.getTextRange().getEndOffset());
        MetricCalculator metricCalculator =
                new MetricCalculator(event.getText(), methodAfterPasting,
                        eventBeginLine, eventEndLine);

        return metricCalculator.getFeaturesVector();
    }
}
