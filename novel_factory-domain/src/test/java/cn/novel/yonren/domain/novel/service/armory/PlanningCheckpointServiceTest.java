package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanningCheckpointServiceTest {

    private IStoryRepository repository;
    private PlanningCheckpointService service;
    private ArmoryCommandEntity command;
    private DefaultArmoryFactory.DynamicContext context;

    @BeforeEach
    void setUp() {
        repository = mock(IStoryRepository.class);
        service = new PlanningCheckpointService(repository);
        command = mock(ArmoryCommandEntity.class);
        context = new DefaultArmoryFactory.DynamicContext();
        context.setJob(new GenerationJob("job-123"));
    }

    @Test
    void initialize_createsDirectoriesAndPersistsInputsBeforePlanningCalls() throws Exception {
        Path storyDir = Path.of("workspace", "20261013-story-0002");
        Path runDir = storyDir.resolve("generation-records/run-job-job-123");
        context.setMaxChapterCount(80);
        when(repository.createStoryDirectory()).thenReturn(storyDir);
        when(repository.createRunDirectory(storyDir, "job-123")).thenReturn(runDir);

        service.initialize(command, context);

        assertEquals(storyDir, context.getStoryDir());
        assertEquals(runDir, context.getRunDir());
        assertEquals("20261013-story-0002", context.getJob().getStoryDirName());
        assertEquals("run-job-job-123", context.getJob().getRunDirName());
        verify(repository).writeStoryBible(storyDir, command);
        verify(repository).writeStoryMeta(storyDir, new IStoryRepository.StoryMeta(80));
        verify(repository).writeGenerationRecord(runDir, command, context);
    }

    @Test
    void planningMilestonesPersistVolumeAndStageArtifacts() throws Exception {
        Path storyDir = Path.of("workspace", "20261013-story-0002");
        context.setStoryDir(storyDir);
        List<VolumeBlueprintEntity> volumes = List.of(new VolumeBlueprintEntity());
        List<StageBlueprintEntity> stages = List.of(new StageBlueprintEntity());
        List<ForeshadowScheduleEntity> schedules = List.of(new ForeshadowScheduleEntity());
        context.setVolumes(volumes);
        context.setStageBlueprints(stages);
        context.setForeshadowSchedules(schedules);

        service.checkpointVolumes(context);
        service.checkpointStages(context);

        verify(repository).writeVolumes(storyDir, volumes);
        verify(repository).writeStageBlueprints(storyDir, stages);
        verify(repository).writeForeshadowSchedule(storyDir, schedules);
    }

    @Test
    void checkpointFailuresAreFailSoft() throws Exception {
        when(repository.createStoryDirectory()).thenThrow(new IllegalStateException("disk busy"));
        context.setStoryDir(Path.of("workspace", "story"));
        context.setVolumes(List.of(new VolumeBlueprintEntity()));
        doThrow(new IllegalStateException("disk busy"))
                .when(repository).writeVolumes(context.getStoryDir(), context.getVolumes());

        assertDoesNotThrow(() -> service.initialize(command, context));
        assertDoesNotThrow(() -> service.checkpointVolumes(context));
    }
}
