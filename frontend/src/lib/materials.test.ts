import { describe, expect, it } from 'vitest'
import { newQuestion, questionProblems, type Question } from './materials'

const good = (over: Partial<Question> = {}): Question => ({
  id: 'q1',
  style: 'EXAM',
  difficulty: 'BEGINNER',
  stem: 'Which normal form removes transitive dependencies?',
  options: ['1NF', '2NF', '3NF', 'BCNF only'],
  answer: 2,
  explanation: '3NF removes transitive dependencies on the key.',
  ...over,
})

describe('questionProblems (mirrors McqRules)', () => {
  it('passes a complete question', () => {
    expect(questionProblems(good(), false)).toEqual([])
  })

  it('needs text, four filled options, one key and an explanation', () => {
    expect(questionProblems(newQuestion(), false)).toEqual(['no question text', 'needs four options', 'no correct answer marked', 'no explanation'])
    expect(questionProblems(good({ options: ['a', 'b', 'c'] }), false)).toContain('needs four options')
    expect(questionProblems(good({ answer: 4 }), false)).toContain('no correct answer marked')
  })

  it('treats options that differ only in case, spaces or punctuation as repeats', () => {
    expect(questionProblems(good({ options: ['3NF', '2NF', ' 3nf. ', '1NF'] }), false)).toEqual(['options repeat'])
  })

  it('flags “all/none of the above” in English and Tamil unless the bank allows it', () => {
    expect(questionProblems(good({ options: ['1NF', '2NF', '3NF', 'All of the above'] }), false)).toEqual(['“all/none of the above” option'])
    expect(questionProblems(good({ options: ['1NF', '2NF', '3NF', 'None of these'] }), false)).toHaveLength(1)
    expect(questionProblems(good({ options: ['1NF', '2NF', '3NF', 'மேற்கண்ட அனைத்தும்'] }), false)).toHaveLength(1)
    expect(questionProblems(good({ options: ['1NF', '2NF', '3NF', 'All of the above'] }), true)).toEqual([])
    // "above" as an ordinary word is fine
    expect(questionProblems(good({ options: ['The row above', '2NF', '3NF', 'Overall cost'] }), false)).toEqual([])
  })

  it('makes new questions with unique ids', () => {
    expect(newQuestion().id).not.toEqual(newQuestion().id)
  })
})
