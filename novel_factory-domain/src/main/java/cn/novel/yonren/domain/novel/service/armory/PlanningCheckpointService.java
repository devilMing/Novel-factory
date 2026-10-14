package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Persists planning milestones as soon as they are produced, so a later
 * transient model failure can resume from disk instead of rebuilding every
 * expensive upstream artifact.
 */
@Service
@Slf4j
public class PlanningCheckpointService {

    private final IStoryRepository storyRepository;

    public PlanningCheckpointService(IStoryRepository storyRepository) {
        this.storyRepository = storyRepository;
    }

    /** Creates the story/run checkpoint and records the original request before the first planning call. */
    public void initialize(ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext context) {
        try {
            Path storyDir = context.getStoryDir();
            if (storyDir == null) {
                storyDir = storyRepository.createStoryDirectory();
                context.setStoryDir(storyDir);
            }
            if (context.getJob() != null) {
                context.getJob().setStoryDirName(storyDir.getFileName().toString());
            }
            Path runDir = context.getRunDir();
            if (runDir == null) {
                runDir = storyRepository.createRunDirectory(storyDir,
                        context.getJob() == null ? null : context.getJob().getJobId());
                context.setRunDir(runDir);
            }
            if (context.getJob() != null) {
                context.getJob().setRunDirName(runDir.getFileName().toString());
            }
            storyRepository.writeStoryBible(storyDir, command);
            if (context.getMaxChapterCount() != null) {
                storyRepository.writeStoryMeta(storyDir,
                        new IStoryRepository.StoryMeta(context.getMaxChapterCount()));
            }
            storyRepository.writeGenerationRecord(runDir, command, context);
            log.info("规划初始检查点已落盘: {}", runDir.toAbsolutePath());
        } catch (Exception e) {
            log.warn("规划初始检查点落盘失败（fail-soft，继续生成）", e);
        }
    }

    public void checkpointVolumes(DefaultArmoryFactory.DynamicContext context) {
        if (context.getStoryDir() == null || context.getVolumes() == null) {
            return;
        }
        try {
            storyRepository.writeVolumes(context.getStoryDir(), context.getVolumes());
            log.info("卷蓝图检查点已落盘: {}", context.getStoryDir().toAbsolutePath());
        } catch (Exception e) {
            log.warn("卷蓝图检查点落盘失败（fail-soft，继续生成）", e);
        }
    }

    public void checkpointStages(DefaultArmoryFactory.DynamicContext context) {
        if (context.getStoryDir() == null) {
            return;
        }
        try {
            if (context.getStageBlueprints() != null) {
                storyRepository.writeStageBlueprints(context.getStoryDir(), context.getStageBlueprints());
            }
            if (context.getForeshadowSchedules() != null) {
                storyRepository.writeForeshadowSchedule(context.getStoryDir(), context.getForeshadowSchedules());
            }
            log.info("阶段蓝图检查点已落盘: {}", context.getStoryDir().toAbsolutePath());
        } catch (Exception e) {
            log.warn("阶段蓝图检查点落盘失败（fail-soft，继续生成）", e);
        }
    }
}
