package org.klozevitz.service.implementations;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j;
import org.klozevitz.model.repositories.ApplicationUserRepository;
import org.klozevitz.repositories.RawDataRepository;
import org.klozevitz.model.entity.ApplicationDocument;
import org.klozevitz.model.entity.ApplicationPhoto;
import org.klozevitz.model.entity.ApplicationUser;
import org.klozevitz.entity.RawData;
import org.klozevitz.model.entity.enums.ApplicationUserState;
import org.klozevitz.exceptions.UploadFileException;
import org.klozevitz.service.enums.LinkType;
import org.klozevitz.service.enums.ServiceCommand;
import org.klozevitz.service.interfaces.ApplicationUserService;
import org.klozevitz.service.interfaces.FileService;
import org.klozevitz.service.interfaces.MainService;
import org.klozevitz.service.interfaces.ProducerService;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import static org.klozevitz.model.entity.enums.ApplicationUserState.BASIC_STATE;
import static org.klozevitz.model.entity.enums.ApplicationUserState.WAIT_FOR_EMAIL_STATE;
import static org.klozevitz.service.enums.ServiceCommand.*;

@Log4j
@Service
@RequiredArgsConstructor
public class MainServiceImplementation implements MainService {
    private final RawDataRepository rawDataRepo;
    private final ApplicationUserRepository appUserRepo;
    private final ProducerService producerService;
    private final FileService fileService;
    private final ApplicationUserService appUserService;

    /**
     * 1) Saving raw data to raw_data table
     * 2) Completing the response
     * 3) Proceeding to response queue
     *
     * The last "else" is here in case of new state that we will not manage to handle
     * */
    @Override
    public void processTextMessage(Update update) {
        saveRawData(update);

        ApplicationUser applicationUser = findOrSaveApplicationUser(update);
        ApplicationUserState userState = applicationUser.getState();
        String command = update.getMessage().getText();
        String output;

        ServiceCommand serviceCommand = ServiceCommand.fromValue(command);
        if (CANCEL.equals(serviceCommand)) {
            output = cancelProcess(applicationUser);
        } else if (BASIC_STATE.equals(userState)) {
            output = processServiceCommand(applicationUser, command);
        } else if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
             output = appUserService.setEmail(applicationUser, command);
        } else {
            log.error("Unknown user state - " + userState);
            output = "Unknown error! Use /cancel to abort current process and then try again!";
        }

        Long chatId = update.getMessage().getChatId();
        sendAnswer(output, chatId);
    }

    @Override
    public void processDocMessage(Update update) {
        saveRawData(update);

        ApplicationUser applicationUser = findOrSaveApplicationUser(update);
        Long chatId = update.getMessage().getChatId();
        if (isNotAllowedToSendContent(chatId, applicationUser)) {
            return;
        }

        String message = null;
        try {
            ApplicationDocument appDoc = fileService.processDoc(update.getMessage());
            String link = fileService.generateLink(appDoc.getId(), LinkType.GET_DOC);
            message = "Document successfully uploaded! Download link:\n" + link;
        } catch (UploadFileException e) {
            log.error(e);
            message = "Upload failed... Try again later.";
        } finally {
            sendAnswer(message, chatId);
        }
    }

    @Override
    public void processPhotoMessage(Update update) {
        saveRawData(update);

        ApplicationUser applicationUser = findOrSaveApplicationUser(update);
        Long chatId = update.getMessage().getChatId();
        if (isNotAllowedToSendContent(chatId, applicationUser)) {
            return;
        }

        String message = null;
        try {
            ApplicationPhoto appPhoto = fileService.processPhoto(update.getMessage());
            String link = fileService.generateLink(appPhoto.getId(), LinkType.GET_PHOTO);
            message = "Photo successfully uploaded! Download link:\n" + link;
        } catch (UploadFileException e) {
            log.error(e);
            message = "Upload failed... Try again later.";
        } finally {
            sendAnswer(message, chatId);
        }
    }

    private boolean isNotAllowedToSendContent(Long chatId, ApplicationUser applicationUser) {
        String error;
        ApplicationUserState state = applicationUser.getState();
        if (!applicationUser.getIsActive()) {
            error = "Sign up or verify your account in order to be able to upload and download files";
            sendAnswer(error, chatId);
            return true;
        } else if (!BASIC_STATE.equals(state)) {
            error = "Use /cancel to abort the current process and upload new file.";
            sendAnswer(error, chatId);
            return true;
        }
        return false;
    }

    private void sendAnswer(String output, Long chatId) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(output);
        producerService.produceAnswer(sendMessage);
    }

    private String processServiceCommand(ApplicationUser applicationUser, String command) {
        ServiceCommand serviceCommand = ServiceCommand.fromValue(command);
        if (REGISTRATION.equals(serviceCommand)) {
            return appUserService.registerUser(applicationUser);
        } else if (HELP.equals(serviceCommand)) {
            return help();
        } else if (START.equals(serviceCommand)) {
            return "Hello!\nUse /help to explore available commands";
        } else {
            return "Unknown command!\nUse /help to explore available commands";
        }
    }

    private String help() {
        return "Available commands:\n" +
                "/cancel - cancel process;\n" +
                "/registration - account registration";
    }

    private String cancelProcess(ApplicationUser appUser) {
        appUser.setState(BASIC_STATE);
        appUserRepo.save(appUser);
        return "Canceled!";
    }

    private void saveRawData(Update update) {
        RawData rawData = RawData.builder()
                .event(update)
                .build();
        rawDataRepo.save(rawData);
    }

    /**
     * Pay attention that the USER on 178 is A TELEGRAM USER
     * persistent - object might have been in database
     * transient - object we are going to save to database
     */
    private ApplicationUser findOrSaveApplicationUser(Update update) {
        User telegramUser = update
                .getMessage()
                .getFrom();
        var persistentApplicationUser =
                appUserRepo.findByTelegramUserId(telegramUser.getId());
        if (persistentApplicationUser.isEmpty()) {
            ApplicationUser transientApplicationUser = ApplicationUser.builder()
                    .telegramUserId(telegramUser.getId())
                    .username(telegramUser.getUserName())
                    .firstName(telegramUser.getFirstName())
                    .lastName(telegramUser.getLastName())
                    .isActive(false)
                    .state(BASIC_STATE)
                    .build();
            return appUserRepo.save(transientApplicationUser);
        }
        return persistentApplicationUser.get();
    }
}
