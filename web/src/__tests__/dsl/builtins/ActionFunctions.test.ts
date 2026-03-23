import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import { Executor } from '../../../dsl/runtime/Executor'
import { Environment } from '../../../dsl/runtime/Environment'
import type { DslValue, ButtonVal, ScheduleVal } from '../../../dsl/runtime/DslValue'
import { toDisplayString, ScheduleFrequency } from '../../../dsl/runtime/DslValue'

function execute(source: string, env?: Environment): DslValue {
  const tokens = new Lexer(source).tokenize()
  const directive = new Parser(tokens, source).parseDirective()
  return new Executor().execute(directive, env ?? Environment.create())
}

describe('ActionFunctions', () => {
  describe('button', () => {
    it('should create button with label and action', () => {
      const result = execute('[button("Click Me", [add(1, 2)])]')
      expect(result.kind).toBe('ButtonVal')
      const btn = result as ButtonVal
      expect(btn.label).toBe('Click Me')
      expect(btn.action.kind).toBe('LambdaVal')
    })

    it('should display button', () => {
      const result = execute('[button("Submit", [add(1, 2)])]')
      expect(toDisplayString(result)).toBe('[Button: Submit]')
    })

    it('should throw when label is missing', () => {
      expect(() => execute('[button()]')).toThrow()
    })

    it('should throw when action is missing', () => {
      expect(() => execute('[button("Click")]')).toThrow()
    })
  })

  describe('schedule', () => {
    it('should create daily schedule', () => {
      const result = execute('[schedule(daily, [add(1, 2)])]')
      expect(result.kind).toBe('ScheduleVal')
      const sched = result as ScheduleVal
      expect(sched.frequency).toBe(ScheduleFrequency.DAILY)
      expect(sched.atTime).toBeNull()
      expect(sched.precise).toBe(false)
    })

    it('should create hourly schedule', () => {
      const result = execute('[schedule(hourly, [add(1, 2)])]')
      expect(result.kind).toBe('ScheduleVal')
      const sched = result as ScheduleVal
      expect(sched.frequency).toBe(ScheduleFrequency.HOURLY)
    })

    it('should create weekly schedule', () => {
      const result = execute('[schedule(weekly, [add(1, 2)])]')
      expect(result.kind).toBe('ScheduleVal')
      const sched = result as ScheduleVal
      expect(sched.frequency).toBe(ScheduleFrequency.WEEKLY)
    })

    it('should display daily schedule', () => {
      const result = execute('[schedule(daily, [add(1, 2)])]')
      expect(toDisplayString(result)).toBe('[Schedule: daily]')
    })

    it('should display hourly schedule', () => {
      const result = execute('[schedule(hourly, [add(1, 2)])]')
      expect(toDisplayString(result)).toBe('[Schedule: hourly]')
    })

    it('should display weekly schedule', () => {
      const result = execute('[schedule(weekly, [add(1, 2)])]')
      expect(toDisplayString(result)).toBe('[Schedule: weekly]')
    })
  })

  describe('daily_at', () => {
    it('should create daily_at schedule', () => {
      const result = execute('[schedule(daily_at("09:00"), [add(1, 2)])]')
      expect(result.kind).toBe('ScheduleVal')
      const sched = result as ScheduleVal
      expect(sched.frequency).toBe(ScheduleFrequency.DAILY)
      expect(sched.atTime).toBe('09:00')
    })

    it('should display daily_at schedule', () => {
      const result = execute('[schedule(daily_at("14:30"), [add(1, 2)])]')
      expect(toDisplayString(result)).toBe('[Schedule: daily at 14:30]')
    })

    it('should throw on invalid time format', () => {
      expect(() => execute('[daily_at("25:00")]')).toThrow()
    })

    it('should throw on non-HH:mm format', () => {
      expect(() => execute('[daily_at("9am")]')).toThrow()
    })
  })

  describe('weekly_at', () => {
    it('should create weekly_at schedule', () => {
      const result = execute('[schedule(weekly_at("10:00"), [add(1, 2)])]')
      expect(result.kind).toBe('ScheduleVal')
      const sched = result as ScheduleVal
      expect(sched.frequency).toBe(ScheduleFrequency.WEEKLY)
      expect(sched.atTime).toBe('10:00')
    })

    it('should display weekly_at schedule', () => {
      const result = execute('[schedule(weekly_at("08:00"), [add(1, 2)])]')
      expect(toDisplayString(result)).toBe('[Schedule: weekly at 08:00]')
    })
  })

  describe('alarm', () => {
    it('should create alarm with id', () => {
      const result = execute('[alarm("abc123")]')
      expect(result.kind).toBe('AlarmVal')
      expect((result as any).alarmId).toBe('abc123')
    })

    it('should display alarm as symbol', () => {
      const result = execute('[alarm("myAlarm")]')
      expect(toDisplayString(result)).toBe('⏰')
    })

    it('should throw when id is missing', () => {
      expect(() => execute('[alarm()]')).toThrow()
    })
  })

  describe('recurringAlarm', () => {
    it('should create alarm with id', () => {
      const result = execute('[recurringAlarm("rec123")]')
      expect(result.kind).toBe('AlarmVal')
      expect((result as any).alarmId).toBe('rec123')
    })

    it('should display recurringAlarm as symbol', () => {
      const result = execute('[recurringAlarm("myRecurring")]')
      expect(toDisplayString(result)).toBe('⏰')
    })

    it('should throw when id is missing', () => {
      expect(() => execute('[recurringAlarm()]')).toThrow()
    })
  })

  describe('frequency constants', () => {
    it('should evaluate daily constant', () => {
      const result = execute('[daily]')
      expect(result.kind).toBe('StringVal')
      expect(toDisplayString(result)).toBe('daily')
    })

    it('should evaluate hourly constant', () => {
      const result = execute('[hourly]')
      expect(result.kind).toBe('StringVal')
      expect(toDisplayString(result)).toBe('hourly')
    })

    it('should evaluate weekly constant', () => {
      const result = execute('[weekly]')
      expect(result.kind).toBe('StringVal')
      expect(toDisplayString(result)).toBe('weekly')
    })
  })
})
